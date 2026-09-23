package dev.streamable.ffmpeg;

import dev.streamable.StreamAbleLog;
import dev.streamable.pipeline.PooledFrame;
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
 * <h2>Measurements</h2>
 * <p>FFmpeg's {@code -progress} report arrives on stdout and is parsed into
 * {@link FFmpegProgress}. Combined with the time each frame was written, it
 * yields the encode latency (frame written to stdin until FFmpeg reports it
 * encoded) and the real output bitrate.</p>
 *
 * <h2>Credentials</h2>
 * <p>The command line contains publish URLs and therefore stream keys. It is
 * never logged as-is: {@link #redactedCommand()} is what goes to the log, and
 * every line of FFmpeg's stderr is redacted before being stored or shown.</p>
 */
public final class FFmpegProcess implements AutoCloseable {

    /** How many recent stderr lines to keep for diagnostics. */
    private static final int STDERR_HISTORY = 80;
    private static final long STOP_GRACE_MILLIS = 8_000;
    private static final int LATENCY_RING = 4096;

    /** Result of offering a frame to the pipeline. */
    public enum FrameResult { ACCEPTED, DROPPED, NOT_RUNNING }

    private final List<String> command;
    private final String[] secrets;
    private final BlockingQueue<Slot> queue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicLong framesWritten = new AtomicLong();
    private final AtomicLong framesDropped = new AtomicLong();
    /** Frames that could not be queued; written later as repeats so the timeline never shrinks. */
    private final AtomicLong framesOwed = new AtomicLong();
    private final AtomicLong framesRepeatedForTiming = new AtomicLong();
    private final Deque<String> stderrHistory = new ArrayDeque<>();
    private final long[] writeTimes = new long[LATENCY_RING];

    private Process process;
    private Thread writerThread;
    private Thread stderrThread;
    private Thread stdoutThread;
    private volatile String lastError = "";
    private volatile boolean publishing;
    private volatile boolean hasDiagnostic;
    private volatile int exitCode = Integer.MIN_VALUE;
    private volatile Runnable onUnexpectedExit;
    private volatile FFmpegProgress progress = FFmpegProgress.NONE;
    private volatile double encodeLatencyMillis = -1;
    private volatile double outputKbps = -1;
    private volatile long startedAtNanos;
    private long lastRateSampleNanos;
    private long lastRateSampleBytes = -1;

    /**
     * @param command       full argument list, starting with the ffmpeg executable
     * @param queueCapacity bounded frame queue depth
     * @param secrets       stream keys to strip from any captured output
     */
    public FFmpegProcess(List<String> command, int queueCapacity, String... secrets) {
        this.command = List.copyOf(command);
        this.queue = new ArrayBlockingQueue<>(Math.clamp(queueCapacity, 2, 600));
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
        ProcessBuilder builder = FFmpegProcesses.builder(command);
        builder.redirectErrorStream(false);
        process = builder.start();
        startedAtNanos = System.nanoTime();
        running.set(true);
        StreamAbleLog.FFMPEG.info("Started FFmpeg: {}", redactedCommand());

        writerThread = Thread.ofPlatform().name("stream-able-ffmpeg-writer").daemon(true).start(this::pumpFrames);
        stderrThread = Thread.ofPlatform().name("stream-able-ffmpeg-stderr").daemon(true).start(this::pumpStderr);
        stdoutThread = Thread.ofPlatform().name("stream-able-ffmpeg-progress").daemon(true).start(this::pumpProgress);
    }

    /** A queued picture and how many consecutive frames it stands for. */
    private record Slot(PooledFrame frame, int repeat) {
    }

    /**
     * Queues a picture for encoding as {@code repeat} consecutive frames.
     *
     * <p>Never blocks. If the queue is full the picture is dropped, but the
     * frames it stood for are remembered as <em>owed</em> and written as
     * repeats of the next picture: FFmpeg derives timestamps from the frame
     * count, so losing the count would shift video against audio. The queue
     * holds its own reference, released once written.</p>
     */
    public FrameResult offerFrame(PooledFrame frame, int repeat) {
        if (!running.get() || stopping.get() || repeat <= 0) {
            return FrameResult.NOT_RUNNING;
        }
        frame.retain();
        if (queue.offer(new Slot(frame, repeat))) {
            return FrameResult.ACCEPTED;
        }
        frame.release();
        framesDropped.incrementAndGet();
        framesOwed.addAndGet(repeat);
        return FrameResult.DROPPED;
    }

    public FrameResult offerFrame(PooledFrame frame) {
        return offerFrame(frame, 1);
    }

    /** Convenience for callers holding a plain array (tests, the stream tester). */
    public FrameResult offerFrame(byte[] frame) {
        PooledFrame wrapped = PooledFrame.wrap(frame);
        try {
            return offerFrame(wrapped, 1);
        } finally {
            wrapped.release();
        }
    }

    private void pumpFrames() {
        OutputStream stdin = process.getOutputStream();
        // The last picture written, kept so owed frames can still be flushed at stop.
        PooledFrame lastWritten = null;
        try {
            while (running.get() || !queue.isEmpty()) {
                Slot slot = queue.poll(200, TimeUnit.MILLISECONDS);
                if (slot == null) {
                    if (stopping.get() && queue.isEmpty()) {
                        break;
                    }
                    continue;
                }
                PooledFrame frame = slot.frame();
                try {
                    long owed = framesOwed.getAndSet(0);
                    long copies = slot.repeat() + owed;
                    if (owed > 0) {
                        framesRepeatedForTiming.addAndGet(owed);
                    }
                    for (long copy = 0; copy < copies; copy++) {
                        stdin.write(frame.data(), 0, frame.length());
                        long index = framesWritten.incrementAndGet();
                        writeTimes[(int) (index % LATENCY_RING)] = System.nanoTime();
                    }
                    frame.retain();
                    if (lastWritten != null) {
                        lastWritten.release();
                    }
                    lastWritten = frame;
                } finally {
                    frame.release();
                }
            }
            // Frames still owed when the session ends are written as repeats of
            // the final picture, so the video is exactly as long as the audio.
            long owed = framesOwed.getAndSet(0);
            if (lastWritten != null) {
                for (long copy = 0; copy < owed; copy++) {
                    stdin.write(lastWritten.data(), 0, lastWritten.length());
                    framesWritten.incrementAndGet();
                }
                framesRepeatedForTiming.addAndGet(owed);
            }
            stdin.flush();
        } catch (IOException e) {
            // Expected when FFmpeg exits first (bad URL, rejected key, ...).
            // The real reason is in stderr, so record it without alarming logs.
            if (!stopping.get() && !hasDiagnostic) {
                lastError = SecretRedactor.redact("FFmpeg input pipe closed: " + e.getMessage(), secrets);
                StreamAbleLog.FFMPEG.debug("FFmpeg stdin closed: {}", lastError);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (lastWritten != null) {
                lastWritten.release();
            }
            Slot leftover;
            while ((leftover = queue.poll()) != null) {
                leftover.frame().release();
            }
            try {
                stdin.close();
            } catch (IOException e) {
                StreamAbleLog.FFMPEG.debug("Failed to close FFmpeg stdin: {}", e.toString());
            }
        }
    }

    private void pumpProgress() {
        FFmpegProgress.Parser parser = new FFmpegProgress.Parser();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                FFmpegProgress block = parser.accept(line);
                if (block != null) {
                    onProgress(block);
                }
            }
        } catch (IOException e) {
            StreamAbleLog.FFMPEG.debug("FFmpeg progress reader ended: {}", e.toString());
        }
    }

    private void onProgress(FFmpegProgress block) {
        progress = block;
        long now = System.nanoTime();
        if (block.frame() > 0) {
            // Only real encoding progress proves the output was opened.
            publishing = true;
            long written = framesWritten.get();
            if (block.frame() <= written && written - block.frame() < LATENCY_RING) {
                long writtenAt = writeTimes[(int) (block.frame() % LATENCY_RING)];
                if (writtenAt > 0) {
                    double latency = (now - writtenAt) / 1_000_000.0;
                    encodeLatencyMillis = encodeLatencyMillis < 0 ? latency : encodeLatencyMillis * 0.7 + latency * 0.3;
                }
            }
        }
        if (block.totalSize() >= 0) {
            if (lastRateSampleBytes >= 0 && now > lastRateSampleNanos) {
                double seconds = (now - lastRateSampleNanos) / 1e9;
                if (seconds >= 0.25) {
                    double kbps = (block.totalSize() - lastRateSampleBytes) * 8.0 / 1000.0 / seconds;
                    outputKbps = outputKbps < 0 ? kbps : outputKbps * 0.6 + kbps * 0.4;
                    lastRateSampleBytes = block.totalSize();
                    lastRateSampleNanos = now;
                }
            } else {
                lastRateSampleBytes = block.totalSize();
                lastRateSampleNanos = now;
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
                if (looksLikeError(safe)) {
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

    static boolean looksLikeError(String line) {
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("error") || lower.contains("failed")
                || lower.contains("invalid") || lower.contains("connection refused")
                || lower.contains("unable to open") || lower.contains("no such")
                || lower.contains("denied") || lower.contains("timed out")
                || lower.contains("cannot load") || lower.contains("not supported");
    }

    /** Closes stdin and waits for FFmpeg to finalise the output; kills it if it will not exit. */
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
                    process.waitFor(3, TimeUnit.SECONDS);
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

    /** Immediate termination, for cancellation and shutdown hooks. */
    public void kill() {
        stopping.set(true);
        running.set(false);
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running.get() && process != null && process.isAlive();
    }

    /** Whether the OS process is still alive (used to prove cleanup in tests). */
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public long pid() {
        return process == null ? -1 : process.pid();
    }

    /**
     * Whether FFmpeg has actually produced output. A running process proves
     * nothing: FFmpeg starts, fails to open the RTMP output, and exits a second
     * later. Only encoded frames in the progress report confirm it.
     */
    public boolean isPublishing() {
        return publishing;
    }

    public FFmpegProgress progress() {
        return progress;
    }

    /** Smoothed time from writing a frame to FFmpeg reporting it encoded; {@code -1} until known. */
    public double encodeLatencyMillis() {
        return encodeLatencyMillis;
    }

    /** Measured bitrate leaving FFmpeg (all outputs), smoothed; {@code -1} until known. */
    public double outputKbps() {
        return outputKbps;
    }

    public double uptimeSeconds() {
        return startedAtNanos == 0 ? 0 : (System.nanoTime() - startedAtNanos) / 1e9;
    }

    public long framesWritten() {
        return framesWritten.get();
    }

    /** Unique pictures lost because the queue was full (their time was kept as repeats). */
    public long framesDropped() {
        return framesDropped.get();
    }

    /** Frames written as repeats to keep the timeline intact after drops. */
    public long framesRepeatedForTiming() {
        return framesRepeatedForTiming.get();
    }

    /** Frames still owed and not yet written. */
    public long framesOwed() {
        return framesOwed.get();
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
