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
    public static final int MIN_BITRATE_KBPS = 100;
    public static final int MAX_BITRATE_KBPS = 400_000;
    public int bitrateKbps = 20_000;
    /** 0 = fastest/lowest quality, 100 = slowest/highest. Maps to encoder presets. */
    public static final int MIN_QUALITY_PRESET = 0;
    public static final int MAX_QUALITY_PRESET = 100;
    public int qualityPreset = 50;

    public long maxFileSizeMb = 0;              // 0 = unlimited
    public boolean autoStopAtMaxSize = true;

    // ---- disk space guardian ----------------------------------------------
    /** Used-% at which Studio / Health warn that the volume is filling. */
    public int diskSpaceWarnPercent = 90;
    /** Used-% at which a recording will not start (and an active one stops). */
    public int diskSpaceBlockPercent = 95;
    /** Free space (MiB) below which a recording will not start and an active one stops. */
    public int diskSpaceMinFreeMb = 500;

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
    public static final int MIN_AUDIO_BITRATE_KBPS = 32;
    public static final int MAX_AUDIO_BITRATE_KBPS = 1024;
    public int audioBitrateKbps = 192;
    public int audioSampleRate = 48_000;
    /** Manual A/V nudge in milliseconds, on top of the measured start offset. */
    public static final int MIN_AUDIO_DELAY_MS = -5_000;
    public static final int MAX_AUDIO_DELAY_MS = 5_000;
    public int audioDelayMs = 0;

    // Replay buffer and automatic clips: see recording/replay. Dead Record-able
    // carry-overs (killMontages, deferred capture, recording-overlay) were
    // retired — never wired in Stream-able; stale JSON keys are ignored on
    // load. watermarkEnabled/watermarkText only seed the new video.watermark
    // once (see StreamAbleConfig.validate).

    // ---- replay buffer and clips ------------------------------------------
    public boolean replayBufferEnabled = false;
    public static final int MIN_REPLAY_SECONDS = 5;
    public static final int MAX_REPLAY_SECONDS = 600;
    public int replayBufferSeconds = 60;
    public boolean autoClipOnDeath = true;
    public boolean autoClipOnAdvancement = false;
    public boolean autoClipOnKill = false;
    public boolean autoClipOnDimensionChange = false;

    // ---- watermark seed (migrated into video.watermark on validate) --------
    public boolean watermarkEnabled = false;
    public String watermarkText = "";

    /** Clamps everything into a usable range after loading untrusted JSON. */
    public void validate() {
        fps = Math.clamp(fps, 1, 240);
        width = Math.clamp(width - (width % 2), 16, 16384);
        height = Math.clamp(height - (height % 2), 16, 16384);
        bitrateKbps = Math.clamp(bitrateKbps, MIN_BITRATE_KBPS, MAX_BITRATE_KBPS);
        qualityPreset = Math.clamp(qualityPreset, MIN_QUALITY_PRESET, MAX_QUALITY_PRESET);
        microphoneGainPercent = Math.clamp(microphoneGainPercent, 0, 400);
        audioBitrateKbps = Math.clamp(audioBitrateKbps, MIN_AUDIO_BITRATE_KBPS, MAX_AUDIO_BITRATE_KBPS);
        audioDelayMs = Math.clamp(audioDelayMs, MIN_AUDIO_DELAY_MS, MAX_AUDIO_DELAY_MS);
        replayBufferSeconds = Math.clamp(replayBufferSeconds, MIN_REPLAY_SECONDS, MAX_REPLAY_SECONDS);
        maxFileSizeMb = Math.max(0, maxFileSizeMb);
        diskSpaceBlockPercent = Math.clamp(diskSpaceBlockPercent, 50, 100);
        diskSpaceWarnPercent = Math.clamp(diskSpaceWarnPercent, 1, diskSpaceBlockPercent - 1);
        diskSpaceMinFreeMb = Math.clamp(diskSpaceMinFreeMb, 100, 1_000_000);
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
