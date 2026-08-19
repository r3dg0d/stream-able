package dev.streamable.recording;

import dev.streamable.StreamAbleLog;
import dev.streamable.audio.AudioMixer;
import dev.streamable.audio.GameAudioTap;
import dev.streamable.audio.WavFileWriter;
import dev.streamable.config.RecordingSettings;
import dev.streamable.ffmpeg.AudioProfile;
import dev.streamable.ffmpeg.FFmpegCapabilityProbe;
import dev.streamable.ffmpeg.FFmpegCommandBuilder;
import dev.streamable.ffmpeg.FFmpegManager;
import dev.streamable.ffmpeg.FFmpegProcess;
import dev.streamable.ffmpeg.RateControl;
import dev.streamable.ffmpeg.VideoEncoder;
import dev.streamable.ffmpeg.VideoProfile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The local recording state machine.
 *
 * <p>Fully independent of {@code StreamController}: stopping a recording never
 * touches a running broadcast, and a broadcast failure never affects the file on
 * disk. The two only share the composed program frame.</p>
 *
 * <h2>Two-pass audio, kept from Record-able</h2>
 * <p>Video is encoded live from raw frames on stdin; audio is captured in
 * parallel to a WAV file and muxed in when the recording stops, offset by the
 * <em>measured</em> gap between the video and audio start instants. The real
 * offset is only knowable once both have actually begun, so applying it at mux
 * time is what keeps long recordings from drifting. Streaming cannot use this
 * approach - there is no "afterwards" - which is why it has its own live audio
 * path.</p>
 */
public final class RecordingController {

    public enum State { IDLE, STARTING, RECORDING, PAUSED, STOPPING }

    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final FFmpegManager ffmpeg;
    private final FFmpegCapabilityProbe probe;
    private final Path gameDirectory;

    private volatile State state = State.IDLE;
    private volatile String lastError = "";
    private FFmpegProcess process;
    private WavFileWriter audioWriter;
    private OutputStream audioTapConsumer;
    private Path videoFile;
    private Path finalFile;
    private VideoProfile activeVideoProfile;
    private AudioProfile activeAudioProfile;
    private long videoStartNanos;
    private long audioStartNanos;
    private long startedAtMillis;
    private long pausedAtMillis;
    private long pausedTotalMillis;

    public RecordingController(FFmpegManager ffmpeg, FFmpegCapabilityProbe probe, Path gameDirectory) {
        this.ffmpeg = ffmpeg;
        this.probe = probe;
        this.gameDirectory = gameDirectory;
    }

    public State state() {
        return state;
    }

    public boolean isRecording() {
        return state == State.RECORDING;
    }

    public boolean isActive() {
        return state == State.RECORDING || state == State.PAUSED;
    }

    public String lastError() {
        return lastError;
    }

    public Path lastOutputFile() {
        return finalFile;
    }

    /**
     * The output directory.
     *
     * <p>Defaults to Record-able's {@code recordings} folder so an upgrading
     * player's existing videos are simply there, already listed, with nothing
     * moved or renamed.</p>
     */
    public Path outputDirectory(RecordingSettings settings) {
        String configured = settings.outputDirectory;
        Path directory = configured == null || configured.isBlank()
                ? gameDirectory.resolve("recordings")
                : Path.of(configured);
        return directory.isAbsolute() ? directory.normalize()
                : gameDirectory.resolve(directory).normalize();
    }

    public long elapsedMillis() {
        if (startedAtMillis == 0) {
            return 0;
        }
        long now = state == State.PAUSED ? pausedAtMillis : System.currentTimeMillis();
        return Math.max(0, now - startedAtMillis - pausedTotalMillis);
    }

    /**
     * Starts recording.
     *
     * @param width  program canvas width
     * @param height program canvas height
     * @return {@code null} on success, or a user-facing error message
     */
    public String start(RecordingSettings settings, int width, int height) {
        if (state != State.IDLE) {
            return "A recording is already running.";
        }
        if (!ffmpeg.isAvailable()) {
            lastError = "FFmpeg is not available. Install it from the Stream-able settings first.";
            return lastError;
        }
        state = State.STARTING;
        try {
            Path directory = outputDirectory(settings);
            Files.createDirectories(directory);

            String stamp = LocalDateTime.now().format(FILE_TIMESTAMP);
            String extension = settings.container.extension;
            videoFile = directory.resolve("stream-able-" + stamp + "-video." + extension);
            finalFile = directory.resolve("stream-able-" + stamp + "." + extension);

            // Recording may use codecs a livestream would reject, so encoder
            // detection is not restricted to stream-safe options here.
            VideoEncoder encoder = probe.resolve(settings.encoder, false);
            // Output resolution comes from the settings; the canvas size passed
            // in is the capture size and is handled by the scale filter.
            activeVideoProfile = new VideoProfile(encoder,
                    settings.width > 0 ? settings.width : width,
                    settings.height > 0 ? settings.height : height,
                    settings.fps,
                    settings.rateControl == null ? RateControl.CONSTANT_QUALITY : settings.rateControl,
                    settings.bitrateKbps, settings.bitrateKbps, settings.bitrateKbps * 2,
                    2.0, VideoProfile.defaultPresetFor(encoder), "high", 2);
            activeAudioProfile = new AudioProfile(settings.audioCodec,
                    settings.audioBitrateKbps, settings.audioSampleRate, 2);

            List<String> command = FFmpegCommandBuilder.buildRecordingCommand(
                    ffmpeg.executable(), activeVideoProfile, width, height,
                    videoFile.toAbsolutePath().toString());

            process = new FFmpegProcess(command, 120);
            process.setOnUnexpectedExit(() -> {
                lastError = "The recording encoder stopped unexpectedly.";
                StreamAbleLog.RECORDING.warn(lastError);
            });
            process.start();
            videoStartNanos = System.nanoTime();

            startAudioCapture(settings, directory, stamp);

            startedAtMillis = System.currentTimeMillis();
            pausedTotalMillis = 0;
            state = State.RECORDING;
            lastError = "";
            StreamAbleLog.RECORDING.info("Recording started: {} ({}x{} @ {} fps, {})",
                    finalFile.getFileName(), width, height, settings.fps, encoder.displayName());
            return null;
        } catch (IOException | RuntimeException e) {
            lastError = "Could not start recording: " + e.getMessage();
            StreamAbleLog.RECORDING.error("Failed to start recording", e);
            cleanupAfterFailure();
            return lastError;
        }
    }

    private void startAudioCapture(RecordingSettings settings, Path directory, String stamp) {
        if (!settings.captureGameAudio) {
            return;
        }
        try {
            Path audioFile = directory.resolve("stream-able-" + stamp + "-audio.wav");
            audioWriter = new WavFileWriter(audioFile, AudioMixer.SAMPLE_RATE, AudioMixer.CHANNELS, 16);
            // Registered through the tap so a simultaneous broadcast keeps its
            // own copy of the game audio.
            audioTapConsumer = audioWriter.asOutputStream();
            GameAudioTap.getInstance().addConsumer(audioTapConsumer);
            audioStartNanos = System.nanoTime();
            StreamAbleLog.RECORDING.debug("Audio capture started {} ms after video.",
                    (audioStartNanos - videoStartNanos) / 1_000_000L);
        } catch (IOException | RuntimeException e) {
            // A missing audio device must not abort the recording.
            StreamAbleLog.RECORDING.warn("Game audio capture unavailable; recording video only", e);
            audioWriter = null;
        }
    }

    /** Queues a composed frame. Never blocks the render thread. */
    public void submitFrame(byte[] frame) {
        if (state != State.RECORDING || frame == null) {
            return;
        }
        FFmpegProcess current = process;
        if (current != null) {
            current.offerFrame(frame);
        }
    }

    public void pause() {
        if (state == State.RECORDING) {
            state = State.PAUSED;
            pausedAtMillis = System.currentTimeMillis();
        }
    }

    public void resume() {
        if (state == State.PAUSED) {
            pausedTotalMillis += System.currentTimeMillis() - pausedAtMillis;
            state = State.RECORDING;
        }
    }

    /**
     * Stops recording and muxes the audio in.
     *
     * @return the final file, or {@code null} if nothing usable was produced
     */
    public Path stop() {
        if (state == State.IDLE || state == State.STOPPING) {
            return finalFile;
        }
        state = State.STOPPING;
        try {
            if (process != null) {
                process.stop();
                process = null;
            }
            if (audioTapConsumer != null) {
                GameAudioTap.getInstance().removeConsumer(audioTapConsumer);
                audioTapConsumer = null;
            }
            if (audioWriter != null) {
                audioWriter.close();
            }

            Path result = muxAudioIfPresent();
            StreamAbleLog.RECORDING.info("Recording finished: {}", result == null ? "no output" : result.getFileName());
            return result;
        } catch (IOException | RuntimeException e) {
            lastError = "Could not finalise the recording: " + e.getMessage();
            StreamAbleLog.RECORDING.error("Failed to finalise recording", e);
            return videoFile;
        } finally {
            audioWriter = null;
            state = State.IDLE;
            startedAtMillis = 0;
        }
    }

    /**
     * Runs the final remux, shifting audio by the measured start gap.
     *
     * <p>A positive {@code -itsoffset} delays audio; because capture always
     * starts after the encoder, the correction is negative by that gap, plus
     * whatever manual nudge the user configured.</p>
     */
    private Path muxAudioIfPresent() {
        if (audioWriter == null || audioWriter.isEmpty()) {
            if (audioWriter != null) {
                StreamAbleLog.RECORDING.info("No audio was captured; keeping the video-only file.");
            }
            finalFile = videoFile;
            return finalFile;
        }
        double startGapSeconds = (audioStartNanos - videoStartNanos) / 1_000_000_000.0;
        List<String> command = FFmpegCommandBuilder.buildMuxCommand(
                ffmpeg.executable(),
                videoFile.toAbsolutePath().toString(),
                audioWriter.path().toAbsolutePath().toString(),
                activeAudioProfile,
                -startGapSeconds,
                finalFile.toAbsolutePath().toString());
        try {
            Process mux = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (var stream = mux.getInputStream()) {
                output = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (mux.waitFor() == 0 && Files.exists(finalFile)) {
                Files.deleteIfExists(videoFile);
                Files.deleteIfExists(audioWriter.path());
                return finalFile;
            }
            StreamAbleLog.RECORDING.warn("Audio mux failed; keeping the separate files. Output:\n{}",
                    output.lines().limit(20).reduce("", (a, b) -> a + System.lineSeparator() + b));
            finalFile = videoFile;
            return finalFile;
        } catch (IOException e) {
            StreamAbleLog.RECORDING.error("Could not run the audio mux", e);
            finalFile = videoFile;
            return finalFile;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finalFile = videoFile;
            return finalFile;
        }
    }

    private void cleanupAfterFailure() {
        if (process != null) {
            process.stop();
            process = null;
        }
        if (audioTapConsumer != null) {
            GameAudioTap.getInstance().removeConsumer(audioTapConsumer);
            audioTapConsumer = null;
        }
        if (audioWriter != null) {
            try {
                audioWriter.close();
            } catch (IOException e) {
                StreamAbleLog.RECORDING.debug("Error closing the audio file after a failed start", e);
            }
            audioWriter = null;
        }
        state = State.IDLE;
    }

    public long framesSubmitted() {
        return process == null ? 0 : process.framesWritten();
    }

    public long framesDropped() {
        return process == null ? 0 : process.framesDropped();
    }

    public double queuePressure() {
        return process == null ? 0 : process.queuePressure();
    }

    public long currentFileSizeBytes() {
        try {
            return videoFile != null && Files.exists(videoFile) ? Files.size(videoFile) : 0;
        } catch (IOException e) {
            return 0;
        }
    }
}
