package dev.streamable.recording.replay;

import dev.streamable.StreamAbleLog;
import dev.streamable.audio.AudioMixer;
import dev.streamable.audio.WavFileWriter;
import dev.streamable.config.RecordingSettings;
import dev.streamable.ffmpeg.AudioCodec;
import dev.streamable.ffmpeg.AudioProfile;
import dev.streamable.ffmpeg.FFmpegCapabilityProbe;
import dev.streamable.ffmpeg.FFmpegCommandBuilder;
import dev.streamable.ffmpeg.FFmpegManager;
import dev.streamable.ffmpeg.FFmpegProcess;
import dev.streamable.ffmpeg.FFmpegProcesses;
import dev.streamable.ffmpeg.RateControl;
import dev.streamable.ffmpeg.VideoEncoder;
import dev.streamable.ffmpeg.VideoProfile;
import dev.streamable.pipeline.PooledFrame;
import dev.streamable.video.OutputValidation;
import dev.streamable.video.Resolution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The replay buffer: the last N seconds, always ready to save as a clip.
 *
 * <h2>How it works</h2>
 * <ul>
 *   <li><b>Video</b> is encoded continuously with the recording's settings
 *       into a rolling ring of 2-second MPEG-TS segments on disk (FFmpeg's
 *       segment muxer, keyframes forced on every boundary). Only
 *       {@code ceil(N / 2) + 3} segment files ever exist.</li>
 *   <li><b>Audio</b> - the program mix - is kept in memory
 *       ({@link ReplayAudioRing}), with the wall-clock time of every sample.</li>
 *   <li><b>Saving</b> copies the newest segments covering N seconds, cuts the
 *       audio for exactly the same wall-clock span, and joins them with a
 *       stream copy (no re-encode). The encoder keeps running meanwhile.</li>
 * </ul>
 *
 * <p>Video and audio line up because the video timeline is constant-frame-rate
 * from its first captured frame (owed frames are repeated, never dropped), so a
 * segment's start time on the encoder timeline maps to one wall-clock instant.</p>
 */
public final class ReplayBuffer {

    public enum State { OFF, RUNNING }

    static final int SEGMENT_SECONDS = 2;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final FFmpegManager ffmpeg;
    private final FFmpegCapabilityProbe probe;
    private final Path gameDirectory;
    private final AudioMixer mixer;
    private final ExecutorService saver = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stream-able-replay-save");
        t.setDaemon(true);
        return t;
    });

    private volatile State state = State.OFF;
    private volatile FFmpegProcess process;
    private volatile Path sessionDir;
    private volatile ReplayAudioRing audio;
    private volatile Consumer<byte[]> audioSink;
    private volatile long videoStartNanos;
    private volatile int seconds;
    private volatile int fps;
    private volatile String lastError = "";
    private volatile Path lastClip;
    private volatile boolean saving;

    public ReplayBuffer(FFmpegManager ffmpeg, FFmpegCapabilityProbe probe, Path gameDirectory, AudioMixer mixer) {
        this.ffmpeg = ffmpeg;
        this.probe = probe;
        this.gameDirectory = gameDirectory;
        this.mixer = mixer;
    }

    public State state() {
        return state;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public boolean isSaving() {
        return saving;
    }

    public String lastError() {
        return lastError;
    }

    public Path lastClip() {
        return lastClip;
    }

    /** Seconds of footage currently available to save. */
    public double bufferedSeconds() {
        Path dir = sessionDir;
        if (dir == null) {
            return 0;
        }
        List<ReplaySegments.Segment> segments = readSegments(dir);
        List<ReplaySegments.Segment> run = ReplaySegments.lastSeconds(segments, seconds);
        return run.isEmpty() ? 0 : run.getLast().end() - run.getFirst().start();
    }

    public int configuredSeconds() {
        return seconds;
    }

    public Path clipsDirectory(Path recordingsDirectory) {
        return recordingsDirectory.resolve("clips");
    }

    /**
     * Starts buffering with the recording's encoder and quality at the given
     * output size. The mixer must already be running for audio to be captured.
     *
     * @return {@code null} when started, otherwise why not
     */
    public synchronized String start(RecordingSettings settings, Resolution output) {
        if (state == State.RUNNING) {
            return null;
        }
        if (!ffmpeg.isAvailable()) {
            return lastError = "FFmpeg is not ready yet.";
        }
        VideoEncoder encoder = probe.resolve(settings.encoder, false);
        String invalid = OutputValidation.firstError(OutputValidation.validate(output, settings.fps, encoder,
                OutputValidation.Target.RECORDING));
        if (invalid != null) {
            return lastError = invalid;
        }
        seconds = Math.clamp(settings.replayBufferSeconds, 5, 600);
        fps = settings.fps;
        try {
            Path root = gameDirectory.resolve("stream-able").resolve("replay-buffer");
            deleteTree(root);   // leftovers from a crash
            Path dir = root.resolve("session-" + System.currentTimeMillis());
            Files.createDirectories(dir);
            int wrap = (int) Math.ceil(seconds / (double) SEGMENT_SECONDS) + 3;
            RateControl rc = settings.rateControl == null ? RateControl.CONSTANT_QUALITY : settings.rateControl;
            VideoProfile video = new VideoProfile(encoder, output.width(), output.height(), settings.fps, rc,
                    settings.bitrateKbps, settings.bitrateKbps, settings.bitrateKbps * 2, SEGMENT_SECONDS,
                    VideoProfile.defaultPresetFor(encoder), "high", 0,
                    VideoProfile.qualityFromSlider(settings.qualityPreset));
            List<String> command = FFmpegCommandBuilder.buildReplaySegmentCommand(ffmpeg.executable(), video,
                    output.width(), output.height(), SEGMENT_SECONDS, wrap,
                    dir.resolve("seg%03d.ts").toString(), dir.resolve("segments.csv").toString());
            FFmpegProcess next = new FFmpegProcess(command, 120);
            next.setOnUnexpectedExit(() -> {
                lastError = "The replay buffer encoder stopped: " + next.lastError();
                StreamAbleLog.RECORDING.warn(lastError);
                state = State.OFF;
            });
            next.start();
            ReplayAudioRing ring = new ReplayAudioRing(seconds + 2.0 * SEGMENT_SECONDS + 2);
            Consumer<byte[]> sink = block -> ring.append(block, System.nanoTime());
            mixer.addSink(sink);
            process = next;
            audio = ring;
            audioSink = sink;
            sessionDir = dir;
            videoStartNanos = 0;
            lastError = "";
            state = State.RUNNING;
            StreamAbleLog.RECORDING.info("Replay buffer started: last {} s at {} {} FPS with {}", seconds,
                    output.label(), settings.fps, encoder.displayName());
            return null;
        } catch (IOException | RuntimeException e) {
            lastError = "Could not start the replay buffer: " + e.getMessage();
            StreamAbleLog.RECORDING.warn("Replay buffer failed to start", e);
            stop();
            return lastError;
        }
    }

    /** Queues a captured frame standing for {@code repeat} output frames. */
    public void submitFrame(PooledFrame frame, int repeat) {
        FFmpegProcess current = process;
        if (state != State.RUNNING || current == null || frame == null) {
            return;
        }
        if (videoStartNanos == 0) {
            videoStartNanos = frame.captureNanos();
        }
        current.offerFrame(frame, repeat);
    }

    public synchronized void stop() {
        state = State.OFF;
        Consumer<byte[]> sink = audioSink;
        if (sink != null) {
            mixer.removeSink(sink);
        }
        audioSink = null;
        FFmpegProcess current = process;
        process = null;
        if (current != null) {
            current.stop();
        }
        Path dir = sessionDir;
        sessionDir = null;
        audio = null;
        if (dir != null && !saving) {
            deleteTree(dir);
        }
    }

    /**
     * Saves the last {@code clipSeconds} (at most the buffer length) as a clip,
     * in the background.
     *
     * @param reason short tag for the file name, e.g. {@code "manual"}, {@code "death"}
     * @return the clip, or a failed future with a user-facing message
     */
    public CompletableFuture<Path> save(String reason, double clipSeconds, Path clipsDirectory,
                                        AudioProfile audioProfile) {
        Path dir = sessionDir;
        ReplayAudioRing ring = audio;
        long start = videoStartNanos;
        if (state != State.RUNNING || dir == null || ring == null || start == 0) {
            return CompletableFuture.failedFuture(new IllegalStateException("The replay buffer is not running."));
        }
        if (saving) {
            return CompletableFuture.failedFuture(new IllegalStateException("A clip is already being saved."));
        }
        saving = true;
        double wanted = Math.clamp(clipSeconds, 1, seconds);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return writeClip(dir, ring, start, reason, wanted, clipsDirectory, audioProfile);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            } finally {
                saving = false;
                if (sessionDir == null) {
                    deleteTree(dir);   // stopped while saving
                }
            }
        }, saver);
    }

    private Path writeClip(Path dir, ReplayAudioRing ring, long videoStart, String reason, double wanted,
                           Path clipsDirectory, AudioProfile audioProfile) throws IOException {
        List<ReplaySegments.Segment> run = ReplaySegments.lastSeconds(readSegments(dir), wanted);
        if (run.isEmpty()) {
            throw new IOException("Nothing buffered yet - wait a few seconds.");
        }
        Path work = Files.createTempDirectory(dir.getParent(), "clip-");
        try {
            // Copy first: the encoder reuses the oldest segment files as it wraps.
            List<String> copies = new ArrayList<>();
            for (int i = 0; i < run.size(); i++) {
                Path copy = work.resolve(String.format(Locale.ROOT, "part%03d.ts", i));
                Files.copy(dir.resolve(run.get(i).file()), copy, StandardCopyOption.REPLACE_EXISTING);
                copies.add(copy.toAbsolutePath().toString());
            }
            Path list = work.resolve("parts.ffconcat");
            Files.writeString(list, ReplaySegments.concatList(copies), StandardCharsets.UTF_8);

            double from = run.getFirst().start();
            double to = run.getLast().end();
            long fromNanos = videoStart + Math.round(from * 1e9);
            long frames = Math.round((to - from) * AudioMixer.SAMPLE_RATE);
            Path wav = work.resolve("audio.wav");
            try (WavFileWriter writer = new WavFileWriter(wav, AudioMixer.SAMPLE_RATE, AudioMixer.CHANNELS, 16)) {
                byte[] pcm = ring.extract(fromNanos, frames);
                writer.write(pcm, 0, pcm.length);
            }

            Files.createDirectories(clipsDirectory);
            String safeReason = reason.replaceAll("[^a-z0-9-]", "");
            Path out = clipsDirectory.resolve("stream-able-clip-" + LocalDateTime.now().format(STAMP)
                    + (safeReason.isEmpty() ? "" : "-" + safeReason) + ".mp4");
            AudioProfile clipAudio = audioProfile.codec() == AudioCodec.AAC ? audioProfile
                    : new AudioProfile(AudioCodec.AAC, 192, AudioMixer.SAMPLE_RATE, 2);
            List<String> command = FFmpegCommandBuilder.buildClipCommand(ffmpeg.executable(), list.toString(),
                    wav.toString(), clipAudio, out.toString());
            Process mux = FFmpegProcesses.builder(command).redirectErrorStream(true).start();
            byte[] log = mux.getInputStream().readAllBytes();
            if (!mux.waitFor(120, TimeUnit.SECONDS) || mux.exitValue() != 0) {
                mux.destroyForcibly();
                String tail = new String(log, StandardCharsets.UTF_8).strip();
                throw new IOException("Could not write the clip" + (tail.isEmpty() ? "." : ": "
                        + tail.lines().reduce((a, b) -> b).orElse("")));
            }
            lastClip = out;
            StreamAbleLog.RECORDING.info("Saved clip {} ({}, {} s)", out.getFileName(), ReplaySegments.describe(run),
                    String.format(Locale.ROOT, "%.1f", to - from));
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while saving the clip.", e);
        } finally {
            deleteTree(work);
        }
    }

    private static List<ReplaySegments.Segment> readSegments(Path dir) {
        try {
            Path list = dir.resolve("segments.csv");
            return Files.exists(list) ? ReplaySegments.parse(Files.readAllLines(list, StandardCharsets.UTF_8))
                    : List.of();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort; a locked file is retried on the next start.
                }
            });
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    public void close() {
        stop();
        saver.shutdown();
    }
}
