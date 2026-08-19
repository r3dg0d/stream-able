package dev.streamable.ffmpeg;

import dev.streamable.StreamAbleLog;
import dev.streamable.util.SecretRedactor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A running FFmpeg process fed raw frames through a bounded queue.
 *
 * <h2>Why the queue is bounded</h2>
 * <p>Writing to FFmpeg's stdin blocks once the pipe buffer fills, which happens
 * as soon as the encoder or the network cannot keep up. Doing that on the render
 * thread would freeze the game. Frames are therefore handed to a bounded queue
 * and written by a dedicated thread; when the queue is full the newest frame is
 * <em>dropped</em> rather than queued. Dropping frames degrades the stream;
 * unbounded queueing degrades the game and then runs out of memory.</p>
 *
 * <h2>Credentials</h2>
 * <p>The command line contains publish URLs and therefore stream keys. It is
 * never logged as-is: {@link #redactedCommand()} is what goes to the log, and
 * every line of FFmpeg's stderr is redacted before being stored or shown.</p>
 */
public final class FFmpegProcess implements AutoCloseable {

    /** How many recent stderr lines to keep for diagnostics. */
    private static final int STDERR_HISTORY = 60;
    private static final long STOP_GRACE_MILLIS = 8_000;

    /** Result of offering a frame to the pipeline. */
    public enum FrameResult { ACCEPTED, DROPPED, NOT_RUNNING }

    private final List<String> command;
    private final String[] secrets;
    private final BlockingQueue<byte[]> queue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicLong framesWritten = new AtomicLong();
    private final AtomicLong framesDropped = new AtomicLong();
    private final Deque<String> stderrHistory = new ArrayDeque<>();

    private Process process;
    private Thread writerThread;
    private Thread stderrThread;
    private volatile String lastError = "";
    /**
     * True once FFmpeg reports encoding progress, which is the only reliable
     * evidence that the output was actually opened and is being written to.
     */
    private volatile boolean publishing;
    /**
     * Set when a genuine FFmpeg diagnostic is seen. Pipe errors are only a
     * symptom of FFmpeg having already exited, so they must not overwrite it.
     */
    private volatile boolean hasDiagnostic;
    private volatile int exitCode = Integer.MIN_VALUE;
    private volatile Runnable onUnexpectedExit;

    /**
     * @param command      full argument list, starting with the ffmpeg executable
     * @param queueCapacity bounded frame queue depth
     * @param secrets      stream keys to strip from any captured output
     */
    public FFmpegProcess(List<String> command, int queueCapacity, String... secrets) {
        this.command = List.copyOf(command);
        this.queue = new ArrayBlockingQueue<>(Math.clamp(queueCapacity, 4, 600));
        this.secrets = secrets == null ? new String[0] : secrets.clone();
    }

    /** Called when FFmpeg exits without {@link #stop()} having been requested. */
    public void setOnUnexpectedExit(Runnable callback) {
        this.onUnexpectedExit = callback;
    }

    /** The command with credentials masked - the only form safe to log or display. */
    public String redactedCommand() {
        return SecretRedactor.redact(String.join(" ", command), secrets);
    }

    public void start() throws IOException {
        if (running.get()) {
            return;
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        process = builder.start();
        running.set(true);
        StreamAbleLog.FFMPEG.info("Started FFmpeg: {}", redactedCommand());

        writerThread = Thread.ofPlatform()
                .name("stream-able-ffmpeg-writer")
                .daemon(true)
                .start(this::pumpFrames);
        stderrThread = Thread.ofPlatform()
                .name("stream-able-ffmpeg-stderr")
                .daemon(true)
                .start(this::pumpStderr);
    }

    /**
     * Queues a frame for encoding.
     *
     * <p>Never blocks: if the pipeline is saturated the frame is dropped and
     * counted, which is visible in the stream health diagnostics.</p>
     */
    public FrameResult offerFrame(byte[] frame) {
        if (!running.get() || stopping.get()) {
            return FrameResult.NOT_RUNNING;
        }
        if (queue.offer(frame)) {
            return FrameResult.ACCEPTED;
        }
        framesDropped.incrementAndGet();
        return FrameResult.DROPPED;
    }

    private void pumpFrames() {
        OutputStream stdin = process.getOutputStream();
        try {
            while (running.get() || !queue.isEmpty()) {
                byte[] frame = queue.poll(200, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    if (stopping.get() && queue.isEmpty()) {
                        break;
                    }
                    continue;
                }
                stdin.write(frame);
                framesWritten.incrementAndGet();
            }
            stdin.flush();
        } catch (IOException e) {
            // Expected when FFmpeg exits first (bad URL, rejected key, ...).
            // The real reason is in stderr, so record it without alarming logs.
            if (!stopping.get() && !hasDiagnostic) {
                // Only meaningful when FFmpeg reported nothing itself: the pipe
                // breaking is what happens *after* FFmpeg dies, not why.
                lastError = SecretRedactor.redact("FFmpeg input pipe closed: " + e.getMessage(), secrets);
                StreamAbleLog.FFMPEG.debug("FFmpeg stdin closed: {}", lastError);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                stdin.close();
            } catch (IOException e) {
                StreamAbleLog.FFMPEG.debug("Failed to close FFmpeg stdin: {}", e.toString());
            }
        }
    }

    private void pumpStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String safe = SecretRedactor.redact(line, secrets);
                synchronized (stderrHistory) {
                    stderrHistory.addLast(safe);
                    while (stderrHistory.size() > STDERR_HISTORY) {
                        stderrHistory.removeFirst();
                    }
                }
                if (isProgress(safe)) {
                    publishing = true;
                } else if (looksLikeError(safe)) {
                    lastError = safe;
                    hasDiagnostic = true;
                    StreamAbleLog.FFMPEG.warn("{}", safe);
                }
            }
        } catch (IOException e) {
            StreamAbleLog.FFMPEG.debug("FFmpeg stderr reader ended: {}", e.toString());
        } finally {
            handleExit();
        }
    }

    private void handleExit() {
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        boolean wasStopping = stopping.get();
        running.set(false);
        if (!wasStopping) {
            StreamAbleLog.FFMPEG.warn("FFmpeg exited unexpectedly with code {}: {}", exitCode,
                    lastError.isEmpty() ? "no error reported" : lastError);
            Runnable callback = onUnexpectedExit;
            if (callback != null) {
                try {
                    callback.run();
                } catch (RuntimeException e) {
                    StreamAbleLog.FFMPEG.error("Error in FFmpeg exit handler", e);
                }
            }
        }
    }

    /**
     * Whether a stderr line is FFmpeg's periodic progress report.
     *
     * <p>FFmpeg only emits these once it is muxing, so seeing one proves the
     * output was opened successfully - which is exactly the signal needed to
     * distinguish "connected and streaming" from "spawned but failing".</p>
     */
    private static boolean isProgress(String line) {
        return line.startsWith("frame=") || line.contains(" time=");
    }

    private static boolean looksLikeError(String line) {
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("error") || lower.contains("failed")
                || lower.contains("invalid") || lower.contains("connection refused")
                || lower.contains("unable to open") || lower.contains("no such")
                || lower.contains("denied") || lower.contains("timed out");
    }

    /** Closes stdin and waits for FFmpeg to finalise the output. */
    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        running.set(false);
        try {
            if (writerThread != null) {
                writerThread.join(STOP_GRACE_MILLIS);
            }
            if (process != null && !process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                StreamAbleLog.FFMPEG.warn("FFmpeg did not exit in time; terminating it.");
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
        }
        if (stderrThread != null) {
            stderrThread.interrupt();
        }
    }

    public boolean isRunning() {
        return running.get() && process != null && process.isAlive();
    }

    /**
     * Whether FFmpeg has actually produced output.
     *
     * <p>A running process proves nothing: FFmpeg starts, fails to open the RTMP
     * output, and exits a second later. Only progress output confirms the stream
     * is genuinely live.</p>
     */
    public boolean isPublishing() {
        return publishing;
    }

    public long framesWritten() {
        return framesWritten.get();
    }

    public long framesDropped() {
        return framesDropped.get();
    }

    public int queueDepth() {
        return queue.size();
    }

    public int queueCapacity() {
        return queue.size() + queue.remainingCapacity();
    }

    /** Queue fullness in {@code [0,1]}; surfaced as "encoder overloaded". */
    public double queuePressure() {
        int capacity = queueCapacity();
        return capacity == 0 ? 0 : queueDepth() / (double) capacity;
    }

    public int exitCode() {
        return exitCode;
    }

    /** Last error line, already redacted. */
    public String lastError() {
        return lastError;
    }

    /** Recent stderr, already redacted - safe to put in a diagnostics export. */
    public String diagnostics() {
        synchronized (stderrHistory) {
            return String.join(System.lineSeparator(), stderrHistory);
        }
    }

    @Override
    public void close() {
        stop();
    }
}
