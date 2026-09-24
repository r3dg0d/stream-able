package dev.streamable.recording;

import dev.streamable.StreamAbleLog;
import dev.streamable.audio.AudioMixer;
import dev.streamable.audio.WavFileWriter;
import dev.streamable.config.RecordingSettings;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The local recording state machine.
 *
 * <p>Fully independent of {@code StreamController}: stopping a recording never
 * touches a running broadcast, and a broadcast failure never affects the file on
 * disk. They share the composed program canvas, but each has its own output
 * resolution, scaling mode and frame rate.</p>
 *
 * <h2>Two-pass audio, kept from Record-able</h2>
 * <p>Video is encoded live from raw frames on stdin; audio is captured in
 * parallel to WAV and muxed in when the recording stops, offset by the
 * <em>measured</em> gap between the first video frame and the first audio
 * sample. The audio comes from the clocked program mixer - the same processed
 * microphone the stream gets - so byte counts are exactly proportional to
 * elapsed time and long recordings do not drift. With "separate tracks" the
 * processed microphone is additionally written to its own track.</p>
 */
public final class RecordingController {

    public enum State { IDLE, STARTING, RECORDING, PAUSED, STOPPING }

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final FFmpegManager ffmpeg;
    private final FFmpegCapabilityProbe probe;
    private final Path gameDirectory;
    private final AudioMixer mixer;

    private volatile State state = State.IDLE;
    private volatile String lastError = "";
    private FFmpegProcess process;
    private WavFileWriter programWriter;
    private WavFileWriter microphoneWriter;
    private Consumer<byte[]> programSink;
    private Consumer<byte[]> microphoneSink;
    private Path directory;
    private Path videoFile;
    private Path finalFile;
    private VideoProfile activeVideoProfile;
    private AudioProfile activeAudioProfile;
    private int audioDelayMs;
    private volatile long videoStartNanos;
    private volatile long programAudioStartNanos;
    private volatile long microphoneAudioStartNanos;
    private long startedAtMillis;
    private long pausedAtMillis;
    private long pausedTotalMillis;
    private long maxFileSizeBytes;
    private boolean autoStopAtMaxSize;
    private volatile boolean stopRequestedBySizeLimit;

    public RecordingController(FFmpegManager ffmpeg, FFmpegCapabilityProbe probe, Path gameDirectory, AudioMixer mixer) {
        this.ffmpeg = ffmpeg;
        this.probe = probe;
        this.gameDirectory = gameDirectory;
        this.mixer = mixer;
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

    public VideoProfile activeVideoProfile() {
        return activeVideoProfile;
    }

    /**
     * The output directory. Defaults to Record-able's {@code recordings}
     * folder so an upgrading player's existing videos are simply there.
     */
    public Path outputDirectory(RecordingSettings settings) {
        String configured = settings.outputDirectory;
        Path path = configured == null || configured.isBlank()
                ? gameDirectory.resolve("recordings")
                : Path.of(configured);
        return path.isAbsolute() ? path.normalize() : gameDirectory.resolve(path).normalize();
    }

    public long elapsedMillis() {
        if (startedAtMillis == 0) {
            return 0;
        }
        long now = state == State.PAUSED ? pausedAtMillis : System.currentTimeMillis();
        return Math.max(0, now - startedAtMillis - pausedTotalMillis);
    }

    /** The encoder a recording would use, resolved without starting anything. */
    public VideoEncoder plannedEncoder(RecordingSettings settings) {
        return probe.resolve(settings.encoder, false);
    }

    /**
     * Validates, then starts recording.
     *
     * @param output the recording output resolution (already resolved against the canvas)
     * @return {@code null} on success, or a user-facing error message
     */
    public String start(RecordingSettings settings, Resolution output) {
        if (state != State.IDLE) {
            return "A recording is already running.";
        }
        if (!ffmpeg.isAvailable()) {
            lastError = "FFmpeg is not ready yet. Stream-able is installing it - see Runtime for progress.";
            return lastError;
        }
        VideoEncoder encoder = probe.resolve(settings.encoder, false);
        String invalid = OutputValidation.firstError(
                OutputValidation.validate(output, settings.fps, encoder, OutputValidation.Target.RECORDING));
        if (invalid == null) {
            invalid = settings.container.problemWith(encoder, settings.audioCodec);
        }
        if (invalid != null) {
            lastError = invalid;
            return invalid;
        }
        state = State.STARTING;
        try {
            directory = outputDirectory(settings);
            Files.createDirectories(directory);

            String stamp = LocalDateTime.now().format(FILE_TIMESTAMP);
            String extension = settings.container.extension;
            videoFile = directory.resolve("stream-able-" + stamp + "-video." + extension);
            finalFile = directory.resolve("stream-able-" + stamp + "." + extension);

            RateControl rateControl = settings.rateControl == null ? RateControl.CONSTANT_QUALITY : settings.rateControl;
            activeVideoProfile = new VideoProfile(encoder, output.width(), output.height(), settings.fps,
                    rateControl, settings.bitrateKbps, settings.bitrateKbps, settings.bitrateKbps * 2,
                    2.0, VideoProfile.defaultPresetFor(encoder), "high", 2,
                    VideoProfile.qualityFromSlider(settings.qualityPreset));
            activeAudioProfile = new AudioProfile(settings.audioCodec, settings.audioBitrateKbps,
                    settings.audioSampleRate, 2);
            audioDelayMs = settings.audioDelayMs;
            maxFileSizeBytes = settings.maxFileSizeMb > 0 ? settings.maxFileSizeMb * 1_048_576L : 0;
            autoStopAtMaxSize = settings.autoStopAtMaxSize;
            stopRequestedBySizeLimit = false;

            List<String> command = FFmpegCommandBuilder.buildRecordingCommand(
                    ffmpeg.executable(), activeVideoProfile, output.width(), output.height(),
                    videoFile.toAbsolutePath().toString());
            process = new FFmpegProcess(command, 120);
            process.setOnUnexpectedExit(() -> {
                lastError = "The recording encoder stopped unexpectedly: " + process.lastError();
                StreamAbleLog.RECORDING.warn(lastError);
            });
            process.start();
            videoStartNanos = 0;   // set by the first frame actually captured

            startAudioCapture(settings, stamp);

            startedAtMillis = System.currentTimeMillis();
            pausedTotalMillis = 0;
            state = State.RECORDING;
            lastError = "";
            StreamAbleLog.RECORDING.info("Recording started: {} ({} @ {} fps, {})",
                    finalFile.getFileName(), output.label(), settings.fps, encoder.displayName());
            return null;
        } catch (IOException | RuntimeException e) {
            lastError = "Could not start recording: " + e.getMessage();
            StreamAbleLog.RECORDING.error("Failed to start recording", e);
            cleanupAfterFailure();
            return lastError;
        }
    }

    private void startAudioCapture(RecordingSettings settings, String stamp) {
        if (!settings.captureGameAudio && !settings.captureMicrophone && !settings.captureVoiceChat) {
            return;
        }
        try {
            programWriter = new WavFileWriter(directory.resolve("stream-able-" + stamp + "-audio.wav"),
                    AudioMixer.SAMPLE_RATE, AudioMixer.CHANNELS, 16);
            programSink = pcm -> writeAudio(programWriter, pcm, true);
            mixer.addSink(programSink);
            if (settings.separateAudioTracks && settings.captureMicrophone) {
                microphoneWriter = new WavFileWriter(directory.resolve("stream-able-" + stamp + "-mic.wav"),
                        AudioMixer.SAMPLE_RATE, AudioMixer.CHANNELS, 16);
                microphoneSink = pcm -> writeAudio(microphoneWriter, pcm, false);
                mixer.addBusSink(dev.streamable.audio.AudioBus.Kind.MICROPHONE, microphoneSink);
            }
        } catch (IOException | RuntimeException e) {
            // A missing audio device must not abort the recording.
            StreamAbleLog.RECORDING.warn("Audio capture unavailable; recording video only", e);
            detachAudio();
        }
    }

    /** Runs on the mixer clock thread. The first write stamps the track's start time. */
    private void writeAudio(WavFileWriter writer, byte[] pcm, boolean program) {
        if (writer == null || state == State.PAUSED) {
            return;
        }
        long now = System.nanoTime();
        try {
            if (writer.isEmpty()) {
                // The block covers the 20 ms before "now"; its first sample is that far back.
                long blockNanos = pcm.length / (long) (AudioMixer.CHANNELS * AudioMixer.BYTES_PER_SAMPLE)
                        * 1_000_000_000L / AudioMixer.SAMPLE_RATE;
                if (program) {
                    programAudioStartNanos = now - blockNanos;
                } else {
                    microphoneAudioStartNanos = now - blockNanos;
                }
            }
            writer.write(pcm, 0, pcm.length);
        } catch (IOException e) {
            StreamAbleLog.RECORDING.warn("Audio track write failed: {}", e.toString());
        }
    }

    /** Queues a captured frame standing for {@code repeat} output frames. Never blocks. */
    public void submitFrame(PooledFrame frame, int repeat) {
        if (state != State.RECORDING || frame == null) {
            return;
        }
        FFmpegProcess current = process;
        if (current != null) {
            if (videoStartNanos == 0) {
                videoStartNanos = frame.captureNanos();
            }
            current.offerFrame(frame, repeat);
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

    /** Client-tick housekeeping: file-size limit. */
    public boolean tick() {
        if (state == State.RECORDING && maxFileSizeBytes > 0 && autoStopAtMaxSize
                && currentFileSizeBytes() >= maxFileSizeBytes && !stopRequestedBySizeLimit) {
            stopRequestedBySizeLimit = true;
            lastError = "Recording stopped at the configured size limit.";
            return true;   // caller stops through the runtime so audio and outputs detach too
        }
        return false;
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
            }
            List<WavFileWriter> writers = detachAudio();
            for (WavFileWriter writer : writers) {
                writer.close();
            }
            Path result = muxAudioIfPresent(writers);
            StreamAbleLog.RECORDING.info("Recording finished: {}", result == null ? "no output" : result.getFileName());
            return result;
        } catch (IOException | RuntimeException e) {
            lastError = "Could not finalise the recording: " + e.getMessage();
            StreamAbleLog.RECORDING.error("Failed to finalise recording", e);
            return videoFile;
        } finally {
            process = null;
            state = State.IDLE;
            startedAtMillis = 0;
        }
    }

    private List<WavFileWriter> detachAudio() {
        if (programSink != null) {
            mixer.removeSink(programSink);
            programSink = null;
        }
        if (microphoneSink != null) {
            mixer.removeBusSink(dev.streamable.audio.AudioBus.Kind.MICROPHONE, microphoneSink);
            microphoneSink = null;
        }
        List<WavFileWriter> writers = new ArrayList<>();
        if (programWriter != null) {
            writers.add(programWriter);
        }
        if (microphoneWriter != null) {
            writers.add(microphoneWriter);
        }
        programWriter = null;
        microphoneWriter = null;
        return writers;
    }

    /**
     * The {@code -itsoffset} for an audio track.
     *
     * <p>FFmpeg's {@code -itsoffset} <em>delays</em> the input it precedes. The
     * first video frame defines t=0; an audio track whose first sample was
     * captured {@code gap} seconds later must therefore be delayed by
     * {@code +gap}, plus the user's manual nudge (positive = later audio).</p>
     */
    static double audioOffsetSeconds(long videoStartNanos, long audioStartNanos, int manualDelayMs) {
        if (videoStartNanos == 0 || audioStartNanos == 0) {
            return manualDelayMs / 1000.0;
        }
        return (audioStartNanos - videoStartNanos) / 1_000_000_000.0 + manualDelayMs / 1000.0;
    }

    private Path muxAudioIfPresent(List<WavFileWriter> writers) {
        List<FFmpegCommandBuilder.AudioTrack> tracks = new ArrayList<>();
        for (WavFileWriter writer : writers) {
            if (writer.isEmpty()) {
                continue;
            }
            boolean program = writer.path().getFileName().toString().endsWith("-audio.wav");
            long start = program ? programAudioStartNanos : microphoneAudioStartNanos;
            tracks.add(new FFmpegCommandBuilder.AudioTrack(writer.path().toAbsolutePath().toString(),
                    audioOffsetSeconds(videoStartNanos, start, audioDelayMs), program ? "Program" : "Microphone"));
        }
        if (tracks.isEmpty()) {
            finalFile = videoFile;
            return finalFile;
        }
        List<String> command = FFmpegCommandBuilder.buildMuxCommand(ffmpeg.executable(),
                videoFile.toAbsolutePath().toString(), tracks, activeAudioProfile, finalFile.toAbsolutePath().toString());
        try {
            Process mux = FFmpegProcesses.builder(command).redirectErrorStream(true).start();
            String output;
            try (var stream = mux.getInputStream()) {
                output = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (mux.waitFor() == 0 && Files.exists(finalFile)) {
                Files.deleteIfExists(videoFile);
                for (WavFileWriter writer : writers) {
                    Files.deleteIfExists(writer.path());
                }
                return finalFile;
            }
            StreamAbleLog.RECORDING.warn("Audio mux failed; keeping the separate files. Output:\n{}",
                    String.join(System.lineSeparator(), output.lines().limit(20).toList()));
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
            process.kill();
            process = null;
        }
        for (WavFileWriter writer : detachAudio()) {
            try {
                writer.close();
            } catch (IOException e) {
                StreamAbleLog.RECORDING.debug("Error closing an audio file after a failed start", e);
            }
        }
        state = State.IDLE;
    }

    // ---- diagnostics -------------------------------------------------------------

    public FFmpegProcess process() {
        return process;
    }

    public long framesSubmitted() {
        FFmpegProcess current = process;
        return current == null ? 0 : current.framesWritten();
    }

    public long framesDropped() {
        FFmpegProcess current = process;
        return current == null ? 0 : current.framesDropped();
    }

    public double queuePressure() {
        FFmpegProcess current = process;
        return current == null ? 0 : current.queuePressure();
    }

    public long currentFileSizeBytes() {
        try {
            return videoFile != null && Files.exists(videoFile) ? Files.size(videoFile) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** Bytes free on the recording volume, or {@code -1}. */
    public long freeDiskBytes() {
        try {
            Path dir = directory != null ? directory : gameDirectory;
            return Files.getFileStore(dir).getUsableSpace();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }
}
