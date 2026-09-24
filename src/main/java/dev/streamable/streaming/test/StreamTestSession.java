package dev.streamable.streaming.test;

import dev.streamable.StreamAbleLog;
import dev.streamable.audio.LiveAudioSender;
import dev.streamable.ffmpeg.EncodeProfile;
import dev.streamable.ffmpeg.FFmpegCommandBuilder;
import dev.streamable.ffmpeg.FFmpegProcess;
import dev.streamable.ffmpeg.FFmpegProgress;
import dev.streamable.pipeline.FramePacer;
import dev.streamable.streaming.StreamDestination;
import dev.streamable.util.SecretRedactor;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One destination test, from connectivity checks to a timed real encode.
 *
 * <p>The encode uses exactly the live pipeline: raw frames written by
 * Stream-able through {@link FFmpegProcess}'s bounded queue, the same encoder
 * arguments ({@link FFmpegCommandBuilder#buildEncodeHead}) and live audio over
 * the loopback socket. Only the output differs - a service's documented test
 * mode, or a local byte-counting sink - so the numbers reflect what a real
 * broadcast at these settings would do on this machine.</p>
 *
 * <p>Isolated from live-stream state: it owns its own process, sockets and
 * threads, and {@link #cancel()} kills the FFmpeg child (and any descendants)
 * so nothing is left running.</p>
 */
public final class StreamTestSession {

    public enum State { IDLE, PREPARING, CONNECTING, TESTING, FINALIZING, COMPLETE, FAILED, CANCELLED }

    private final StreamDestination destination;
    private final EncodeProfile profile;
    private final String ffmpegExecutable;
    private final int durationSeconds;
    private final StreamTestPlan plan;
    private final NetworkProbe probe;

    private volatile State state = State.IDLE;
    private volatile String message = "";
    private volatile StreamTestMetrics metrics = StreamTestMetrics.EMPTY;
    private volatile boolean cancelled;
    private volatile FFmpegProcess process;
    private volatile ServerSocket sink;
    private volatile LiveAudioSender audio;
    private final AtomicLong sinkBytes = new AtomicLong();
    private Thread worker;
    private final List<NetworkProbe.Check> checks = new ArrayList<>();

    public StreamTestSession(StreamDestination destination, EncodeProfile profile, String ffmpegExecutable,
                             int durationSeconds, NetworkProbe probe) {
        this.destination = destination;
        this.profile = profile;
        this.ffmpegExecutable = ffmpegExecutable;
        this.durationSeconds = Math.clamp(durationSeconds, 5, 120);
        this.plan = StreamTestPlan.forDestination(destination);
        this.probe = probe;
    }

    public State state() {
        return state;
    }

    public boolean isRunning() {
        return switch (state) {
            case PREPARING, CONNECTING, TESTING, FINALIZING -> true;
            default -> false;
        };
    }

    public String message() {
        return message;
    }

    public StreamTestPlan plan() {
        return plan;
    }

    public StreamTestMetrics metrics() {
        return metrics;
    }

    public StreamDestination destination() {
        return destination;
    }

    public synchronized void start() {
        if (state != State.IDLE) {
            return;
        }
        state = State.PREPARING;
        worker = Thread.ofPlatform().name("stream-able-stream-test").daemon(true).start(this::run);
    }

    /** Stops the test; safe from any thread, idempotent. */
    public void cancel() {
        cancelled = true;
        cleanup(true);
        Thread current = worker;
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (isRunning()) {
            state = State.CANCELLED;
            message = "Test cancelled.";
        }
    }

    private String redact(String text) {
        return SecretRedactor.redact(text, destination.credentials().streamKey());
    }

    private volatile String connectivityProblem;

    private void run() {
        try {
            message = "Checking the destination...";
            String invalid = destination.validate();
            if (invalid != null) {
                fail(invalid);
                return;
            }
            state = State.CONNECTING;
            message = "Checking DNS, the connection and the RTMP handshake...";
            checks.addAll(probe.run(destination.credentials().ingestUrl(), () -> cancelled));
            publishMetrics(0, FFmpegProgress.NONE, "Not started");
            if (cancelled) {
                return;
            }
            if (checks.stream().anyMatch(c -> c.status() == NetworkProbe.Status.FAILED)) {
                NetworkProbe.Check failed = checks.stream().filter(c -> c.status() == NetworkProbe.Status.FAILED)
                        .findFirst().orElseThrow();
                String problem = failed.name() + " failed: " + redact(failed.detail());
                if (plan.mode() == StreamTestPlan.Mode.SERVICE_BANDWIDTH_TEST) {
                    fail(problem);   // nothing to measure without the service
                    return;
                }
                // The local encoder test does not need the server, so it still
                // runs; the connectivity problem is reported alongside it.
                connectivityProblem = problem;
            }
            runEncode();
        } catch (RuntimeException | IOException e) {
            fail("The test failed: " + redact(String.valueOf(e.getMessage())));
            StreamAbleLog.STREAMING.warn("Stream test failed: {}", redact(e.toString()));
        } finally {
            cleanup(false);
        }
    }

    private void runEncode() throws IOException {
        String output;
        if (plan.mode() == StreamTestPlan.Mode.SERVICE_BANDWIDTH_TEST) {
            output = StreamTestPlan.bandwidthTestUrl(destination);
        } else {
            ServerSocket server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            sink = server;
            Thread.ofPlatform().name("stream-able-stream-test-sink").daemon(true).start(() -> drain(server));
            output = "tcp://127.0.0.1:" + server.getLocalPort();
        }
        audio = new LiveAudioSender();
        List<String> command = new ArrayList<>(FFmpegCommandBuilder.buildEncodeHead(ffmpegExecutable, profile,
                profile.video().width(), profile.video().height(), audio.port(), 0));
        command.add("-flvflags");
        command.add("no_duration_filesize");
        command.add("-f");
        command.add("flv");
        command.add(output);

        FFmpegProcess ffmpeg = new FFmpegProcess(command, 60, destination.credentials().streamKey());
        process = ffmpeg;
        state = State.TESTING;
        message = plan.mode() == StreamTestPlan.Mode.SERVICE_BANDWIDTH_TEST
                ? "Sending a bandwidth test to Twitch (not public)..."
                : "Encoding locally at your stream settings (nothing is published)...";
        ffmpeg.start();

        int width = profile.video().width();
        int height = profile.video().height();
        byte[][] frames = TestPattern.frames(width, height, 3);
        FramePacer pacer = new FramePacer(profile.video().fps());
        byte[] tone = TestPattern.toneBlock(profile.audio().sampleRate());
        long start = System.nanoTime();
        long lastAudio = start;
        long lastReport = 0;
        double maxPressure = 0;
        long frameIndex = 0;
        while (!cancelled) {
            long now = System.nanoTime();
            double elapsed = (now - start) / 1e9;
            if (elapsed >= durationSeconds) {
                break;
            }
            if (!ffmpeg.isRunning()) {
                fail("FFmpeg stopped during the test: " + redact(ffmpeg.lastError().isEmpty()
                        ? "exit code " + ffmpeg.exitCode() : ffmpeg.lastError()));
                return;
            }
            if (elapsed > 15 && !ffmpeg.isPublishing()) {
                fail("FFmpeg produced no output within 15 seconds. " + redact(ffmpeg.lastError()));
                return;
            }
            int due = pacer.framesDue(now);
            if (due > 0) {
                ffmpeg.offerFrame(dev.streamable.pipeline.PooledFrame.wrap(frames[(int) (frameIndex++ % frames.length)]), due);
            }
            while (now - lastAudio >= 20_000_000L) {
                audio.write(tone);
                lastAudio += 20_000_000L;
            }
            maxPressure = Math.max(maxPressure, ffmpeg.queuePressure());
            if (now - lastReport > 250_000_000L) {
                lastReport = now;
                publishMetrics(elapsed, ffmpeg.progress(), "Running", maxPressure);
            }
            sleepUntilNextFrame(pacer, start);
        }
        if (cancelled) {
            return;
        }
        state = State.FINALIZING;
        message = "Finishing...";
        double elapsed = (System.nanoTime() - start) / 1e9;
        FFmpegProgress last = ffmpeg.progress();
        publishMetrics(elapsed, last, "Completed", maxPressure);
        ffmpeg.stop();
        // Only the service test mode actually measures upload bandwidth.
        metrics = withFindings(metrics, plan.mode() == StreamTestPlan.Mode.SERVICE_BANDWIDTH_TEST, connectivityProblem);
        state = State.COMPLETE;
        message = connectivityProblem == null ? "Test complete."
                : "The local encoder test finished, but the server could not be reached.";
    }

    private void sleepUntilNextFrame(FramePacer pacer, long start) {
        long next = start + pacer.deadlineOffsetNanos(pacer.emittedFrames());
        long wait = Math.min(next - System.nanoTime(), 20_000_000L);
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelled = true;
            }
        }
    }

    private void drain(ServerSocket server) {
        try (Socket client = server.accept(); InputStream in = client.getInputStream()) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                sinkBytes.addAndGet(read);
            }
        } catch (IOException e) {
            // closed at the end of the test
        }
    }

    private void publishMetrics(double elapsed, FFmpegProgress progress, String status) {
        publishMetrics(elapsed, progress, status, 0);
    }

    private void publishMetrics(double elapsed, FFmpegProgress progress, String status, double maxPressure) {
        FFmpegProcess current = process;
        int target = profile.video().bitrateKbps() + profile.audio().bitrateKbps();
        double achieved = current == null ? -1 : current.outputKbps();
        double average = progress.totalSize() > 0 && elapsed > 1 ? progress.totalSize() * 8.0 / 1000.0 / elapsed : -1;
        metrics = new StreamTestMetrics(target, achieved, average,
                progress.frame() > 0 ? progress.fps() : -1, profile.video().fps(),
                current == null ? -1 : current.encodeLatencyMillis(), progress.speed(),
                current == null ? 0 : current.framesDropped(), maxPressure, 0, elapsed,
                redact(status), List.copyOf(checks), List.of());
    }

    /** Plain-language interpretation of the numbers. */
    static StreamTestMetrics withFindings(StreamTestMetrics m, boolean serviceMeasured) {
        return withFindings(m, serviceMeasured, null);
    }

    /**
     * @param connectivityProblem why the ingest could not be reached, or {@code null}; reported first
     *                            so a clean encoder result is never mistaken for a working destination
     */
    static StreamTestMetrics withFindings(StreamTestMetrics m, boolean serviceMeasured, String connectivityProblem) {
        List<String> findings = new ArrayList<>();
        if (connectivityProblem != null) {
            findings.add(connectivityProblem + " Going live to this destination would fail until that is fixed.");
        }
        if (m.encoderFps() > 0 && m.encoderFps() < m.targetFps() * 0.95) {
            findings.add(String.format(Locale.ROOT, "Encoder cannot maintain %d FPS (reached %.1f). Try a hardware "
                    + "encoder, a faster preset, a lower resolution or a lower frame rate.", m.targetFps(), m.encoderFps()));
        }
        if (m.speed() > 0 && m.speed() < 0.97) {
            findings.add(String.format(Locale.ROOT, "Encoding ran at %.2fx real time; the stream would fall behind.", m.speed()));
        }
        if (serviceMeasured && m.averageKbps() > 0 && m.averageKbps() < m.targetKbps() * 0.9) {
            findings.add(String.format(Locale.ROOT, "Upload bandwidth is below the configured bitrate "
                    + "(%.0f of %d kbps). Lower the bitrate or check your connection.", m.averageKbps(), m.targetKbps()));
        }
        if (m.maxQueuePressure() > 0.5) {
            findings.add(String.format(Locale.ROOT, "The frame queue filled to %.0f %%: the encoder or connection "
                    + "is struggling to keep up.", m.maxQueuePressure() * 100));
        }
        if (m.droppedFrames() > 0) {
            findings.add(m.droppedFrames() + " frames had to be dropped during the test.");
        }
        if (m.encodeLatencyMillis() > 150) {
            findings.add(String.format(Locale.ROOT, "Average encode latency is high (%.0f ms).", m.encodeLatencyMillis()));
        }
        if (findings.isEmpty() || (connectivityProblem != null && findings.size() == 1)) {
            findings.add(String.format(Locale.ROOT, "Your settings held %d FPS at %d kbps without drops.",
                    m.targetFps(), m.targetKbps()));
        }
        return new StreamTestMetrics(m.targetKbps(), m.achievedKbps(), m.averageKbps(), m.encoderFps(), m.targetFps(),
                m.encodeLatencyMillis(), m.speed(), m.droppedFrames(), m.maxQueuePressure(), m.reconnects(),
                m.elapsedSeconds(), m.ffmpegStatus(), m.networkChecks(), List.copyOf(findings));
    }

    private void fail(String reason) {
        if (cancelled) {
            return;
        }
        message = redact(reason);
        metrics = new StreamTestMetrics(metrics.targetKbps(), metrics.achievedKbps(), metrics.averageKbps(),
                metrics.encoderFps(), metrics.targetFps(), metrics.encodeLatencyMillis(), metrics.speed(),
                metrics.droppedFrames(), metrics.maxQueuePressure(), metrics.reconnects(), metrics.elapsedSeconds(),
                "Failed", List.copyOf(checks), List.of(message));
        state = State.FAILED;
    }

    private void cleanup(boolean force) {
        FFmpegProcess current = process;
        if (current != null) {
            if (force) {
                current.kill();
            } else if (current.isAlive()) {
                current.stop();
            }
        }
        LiveAudioSender sender = audio;
        if (sender != null) {
            sender.close();
        }
        ServerSocket server = sink;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    /** Whether the FFmpeg child is still alive (test hook for cleanup). */
    public boolean childAlive() {
        FFmpegProcess current = process;
        return current != null && current.isAlive();
    }

    public long sinkBytes() {
        return sinkBytes.get();
    }
}
