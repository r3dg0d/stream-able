package dev.streamable.config;

import dev.streamable.ffmpeg.AudioCodec;
import dev.streamable.ffmpeg.RateControl;
import dev.streamable.ffmpeg.VideoEncoder;

/**
 * Local recording settings, carried over from Record-able.
 *
 * <p>Mutable with public fields because this is a Gson-mapped configuration
 * object edited directly by the settings UI; {@link #validate()} is the single
 * place that repairs out-of-range values after a load.</p>
 */
public final class RecordingSettings {

    /** Container formats Record-able supported, all preserved. */
    public enum Container {
        MP4("mp4"), MKV("mkv"), MOV("mov"), WEBM("webm");

        public final String extension;

        Container(String extension) {
            this.extension = extension;
        }

        /** Whether FFmpeg's muxer for this container accepts the video codec (measured with FFmpeg 8.1). */
        public boolean supports(VideoEncoder.Codec codec) {
            return switch (this) {
                case MP4, MKV -> true;
                case MOV -> codec == VideoEncoder.Codec.H264 || codec == VideoEncoder.Codec.HEVC;
                case WEBM -> codec == VideoEncoder.Codec.VP9 || codec == VideoEncoder.Codec.AV1;
            };
        }

        /** Whether FFmpeg's muxer for this container accepts the audio codec (measured with FFmpeg 8.1). */
        public boolean supports(AudioCodec codec) {
            return switch (this) {
                case MP4, MKV -> true;
                case MOV -> codec == AudioCodec.AAC || codec == AudioCodec.PCM;
                case WEBM -> codec == AudioCodec.OPUS;
            };
        }

        /**
         * Why this container cannot hold the codecs, or {@code null} when it can.
         * Checked before recording starts, since the muxer would otherwise fail.
         */
        public String problemWith(VideoEncoder encoder, AudioCodec audio) {
            if (encoder != null && !supports(encoder.codec())) {
                return name() + " cannot hold " + encoder.codec().name() + " video; choose MKV or MP4, "
                        + "or a different encoder.";
            }
            if (audio != null && !supports(audio)) {
                return name() + " cannot hold " + audio.name() + " audio; choose MKV or MP4, or a different "
                        + "audio codec.";
            }
            return null;
        }
    }

    public boolean enabled = true;
    /** Empty means "the default .minecraft/stream-able/recordings folder". */
    public String outputDirectory = "";
    public Container container = Container.MP4;

    public int fps = 60;
    public int width = 1920;
    public int height = 1080;
    /** Empty means auto-detect: the best encoder that passed its test encode, hardware first. */
    public String encoder = "";
    public RateControl rateControl = RateControl.CONSTANT_QUALITY;
    public int bitrateKbps = 20_000;
    /** 0 = fastest/lowest quality, 100 = slowest/highest. Maps to encoder presets. */
    public int qualityPreset = 50;

    public long maxFileSizeMb = 0;              // 0 = unlimited
    public boolean autoStopAtMaxSize = true;

    // ---- audio -------------------------------------------------------------
    public boolean captureGameAudio = true;
    public boolean captureMicrophone = false;
    public boolean pushToTalk = false;
    public int microphoneGainPercent = 100;
    /** Input device name; blank means the system default. */
    public String microphoneDevice = "";
    public boolean noiseSuppression = false;
    /** Keeps game and microphone on distinct tracks in the recording. */
    public boolean separateAudioTracks = false;
    /** Capture Plasmo Voice proximity chat into recordings and streams. */
    public boolean captureVoiceChat = true;
    public AudioCodec audioCodec = AudioCodec.AAC;
    public int audioBitrateKbps = 192;
    public int audioSampleRate = 48_000;
    /** Manual A/V nudge in milliseconds, on top of the measured start offset. */
    public int audioDelayMs = 0;

    // ---- replay buffer and clips ------------------------------------------
    public boolean replayBufferEnabled = false;
    public int replayBufferSeconds = 60;
    public boolean autoClipOnDeath = true;
    public boolean autoClipOnAdvancement = false;
    public boolean autoClipOnKill = false;
    public boolean autoClipOnDimensionChange = false;
    public boolean killMontages = false;

    // ---- deferred capture --------------------------------------------------
    public boolean deferredCapture = false;
    public int deferredCaptureFps = 15;
    public int deferredOutputFps = 60;
    public boolean deferredInterpolation = false;

    // ---- overlay -----------------------------------------------------------
    public boolean showRecordingOverlay = true;
    public int overlayPosition = 0;
    public float overlayScale = 1.0f;
    public boolean watermarkEnabled = false;
    public String watermarkText = "";

    /** Clamps everything into a usable range after loading untrusted JSON. */
    public void validate() {
        fps = Math.clamp(fps, 1, 240);
        width = Math.clamp(width - (width % 2), 16, 16384);
        height = Math.clamp(height - (height % 2), 16, 16384);
        bitrateKbps = Math.clamp(bitrateKbps, 100, 400_000);
        qualityPreset = Math.clamp(qualityPreset, 0, 100);
        microphoneGainPercent = Math.clamp(microphoneGainPercent, 0, 400);
        audioBitrateKbps = Math.clamp(audioBitrateKbps, 32, 1024);
        audioDelayMs = Math.clamp(audioDelayMs, -5000, 5000);
        replayBufferSeconds = Math.clamp(replayBufferSeconds, 5, 600);
        deferredCaptureFps = Math.clamp(deferredCaptureFps, 1, 60);
        deferredOutputFps = Math.clamp(deferredOutputFps, 15, 240);
        overlayScale = (float) Math.clamp(overlayScale, 0.5, 2.0);
        overlayPosition = Math.clamp(overlayPosition, 0, 4);
        maxFileSizeMb = Math.max(0, maxFileSizeMb);
        if (container == null) {
            container = Container.MP4;
        }
        if (audioCodec == null) {
            audioCodec = AudioCodec.AAC;
        }
        if (rateControl == null) {
            rateControl = RateControl.CONSTANT_QUALITY;
        }
        if (encoder == null) {
            encoder = "";   // auto-detect
        }
        if (outputDirectory == null) {
            outputDirectory = "";
        }
        if (microphoneDevice == null) {
            microphoneDevice = "";
        }
        if (watermarkText == null) {
            watermarkText = "";
        }
    }
}
