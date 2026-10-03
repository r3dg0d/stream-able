package dev.streamable.config;

import dev.streamable.ffmpeg.AudioCodec;
import dev.streamable.ffmpeg.AudioProfile;
import dev.streamable.ffmpeg.EncodeProfile;
import dev.streamable.ffmpeg.RateControl;
import dev.streamable.ffmpeg.VideoEncoder;
import dev.streamable.ffmpeg.VideoProfile;
import dev.streamable.streaming.ReconnectPolicy;
import dev.streamable.streaming.StreamPlatform;

import java.util.ArrayList;
import java.util.List;

/** Broadcast settings and the list of destinations. */
public final class StreamingSettings {

    /** Persisted form of a destination. Runtime state lives on {@code StreamDestination}. */
    public static final class Destination {
        public String id = "";
        public String name = "";
        public StreamPlatform platform = StreamPlatform.CUSTOM;
        public boolean enabled = true;
        public String ingestUrl = "";
        /**
         * The stream key. Written to the config file, which is why
         * {@code ConfigIo} tightens the file permissions whenever any
         * destination has one.
         */
        public String streamKey = "";
    }

    /** Hides advanced encoder fields behind a simple/advanced toggle. */
    public boolean advancedMode = false;

    public List<Destination> destinations = new ArrayList<>();

    // ---- video -------------------------------------------------------------
    /** Legacy (schema 1) stream size; migrated into {@code video.streaming}. */
    public int width = 1920;
    public int height = 1080;
    public int fps = 60;
    /** Blank means "auto-detect the best available encoder". */
    public String encoder = "";
    public RateControl rateControl = RateControl.CBR;
    public int bitrateKbps = 6000;
    public int maxBitrateKbps = 6000;
    public int bufferSizeKbits = 12_000;
    public double keyframeSeconds = 2.0;
    public String preset = "";
    public String h264Profile = "high";
    public int bFrames = 0;

    // ---- audio -------------------------------------------------------------
    public AudioCodec audioCodec = AudioCodec.AAC;
    public int audioBitrateKbps = 160;
    public int audioSampleRate = 48_000;

    // ---- reliability -------------------------------------------------------
    /**
     * Shared by {@link #validate()} and the Studio reconnect fields.
     * Attempts of {@code 0} mean unlimited ({@code ReconnectPolicy}).
     */
    public static final int MIN_RECONNECT_DELAY_MS = 250;
    public static final int MAX_INITIAL_RECONNECT_DELAY_MS = 300_000;
    public static final int MAX_RECONNECT_DELAY_MS = 900_000;
    public static final int MAX_RECONNECT_ATTEMPTS = 1000;

    public boolean reconnect = true;
    public long reconnectDelayMs = 5_000;
    public long maxReconnectDelayMs = 60_000;
    public int maxReconnectAttempts = 10;

    /** Bounded frame queue depth before frames start being dropped. */
    public int frameQueueCapacity = 90;

    /** Measured upstream capacity; zero means unknown. 20% is reserved at session start. */
    public double uploadSpeedMbps = 0;

    /**
     * Lowest longest-wait the Studio field will accept: the current first retry,
     * already clamped to the same range {@link #validate()} uses for that delay.
     */
    public int longestWaitMinMs() {
        return (int) Math.clamp(reconnectDelayMs, MIN_RECONNECT_DELAY_MS, MAX_INITIAL_RECONNECT_DELAY_MS);
    }

    /**
     * Studio edit for the first retry. Raises the longest wait when the new delay
     * would leave it shorter, which is what {@link #validate()} does on save.
     *
     * @return false when {@code value} is outside the validated range and nothing changed
     */
    public boolean trySetReconnectDelayMs(long value) {
        if (value < MIN_RECONNECT_DELAY_MS || value > MAX_INITIAL_RECONNECT_DELAY_MS) {
            return false;
        }
        reconnectDelayMs = value;
        if (maxReconnectDelayMs < value) {
            maxReconnectDelayMs = value;
        }
        return true;
    }

    /**
     * Studio edit for the longest wait. Rejects a ceiling below the first retry
     * instead of storing it for {@link #validate()} to fix later.
     *
     * @return false when {@code value} is outside the validated range and nothing changed
     */
    public boolean trySetMaxReconnectDelayMs(long value) {
        if (value < longestWaitMinMs() || value > MAX_RECONNECT_DELAY_MS) {
            return false;
        }
        maxReconnectDelayMs = value;
        return true;
    }

    /**
     * Studio edit for the attempt cap. {@code 0} is unlimited.
     *
     * @return false when {@code value} is outside {@code 0}..{@link #MAX_RECONNECT_ATTEMPTS}
     */
    public boolean trySetMaxReconnectAttempts(int value) {
        if (value < 0 || value > MAX_RECONNECT_ATTEMPTS) {
            return false;
        }
        maxReconnectAttempts = value;
        return true;
    }

    public ReconnectPolicy reconnectPolicy() {
        return new ReconnectPolicy(reconnect, reconnectDelayMs, maxReconnectDelayMs, 2.0, maxReconnectAttempts);
    }

    /** The encode profile shared by destinations without an override. */
    public EncodeProfile encodeProfile(VideoEncoder resolvedEncoder) {
        return encodeProfile(resolvedEncoder, new dev.streamable.video.Resolution(
                Math.max(16, width), Math.max(16, height)));
    }

    /** The encode profile at an explicit output resolution (from the video settings). */
    public EncodeProfile encodeProfile(VideoEncoder resolvedEncoder, dev.streamable.video.Resolution output) {
        VideoProfile video = new VideoProfile(resolvedEncoder, output.width(), output.height(), fps, rateControl,
                bitrateKbps, maxBitrateKbps, bufferSizeKbits, keyframeSeconds,
                preset.isBlank() ? VideoProfile.defaultPresetFor(resolvedEncoder) : preset,
                h264Profile, bFrames);
        return new EncodeProfile(video, new AudioProfile(audioCodec, audioBitrateKbps, audioSampleRate, 2));
    }

    public void validate() {
        width = Math.clamp(width - (width % 2), 16, 16384);
        height = Math.clamp(height - (height % 2), 16, 16384);
        fps = Math.clamp(fps, 1, 240);
        bitrateKbps = Math.clamp(bitrateKbps, 100, 200_000);
        maxBitrateKbps = maxBitrateKbps <= 0 ? bitrateKbps : Math.clamp(maxBitrateKbps, 100, 400_000);
        bufferSizeKbits = bufferSizeKbits <= 0 ? bitrateKbps * 2 : Math.clamp(bufferSizeKbits, 100, 800_000);
        keyframeSeconds = keyframeSeconds <= 0 ? 2.0 : Math.clamp(keyframeSeconds, 0.5, 10.0);
        audioBitrateKbps = Math.clamp(audioBitrateKbps, 32, 512);
        bFrames = Math.clamp(bFrames, 0, 8);
        maxReconnectAttempts = Math.clamp(maxReconnectAttempts, 0, MAX_RECONNECT_ATTEMPTS);
        reconnectDelayMs = Math.clamp(reconnectDelayMs, MIN_RECONNECT_DELAY_MS, MAX_INITIAL_RECONNECT_DELAY_MS);
        maxReconnectDelayMs = Math.clamp(maxReconnectDelayMs, reconnectDelayMs, MAX_RECONNECT_DELAY_MS);
        frameQueueCapacity = Math.clamp(frameQueueCapacity, 8, 600);
        uploadSpeedMbps = Double.isFinite(uploadSpeedMbps) ? Math.clamp(uploadSpeedMbps, 0, 10000) : 0;
        if (audioCodec == null) {
            audioCodec = AudioCodec.AAC;
        }
        if (rateControl == null || !rateControl.isLiveSafe()) {
            rateControl = RateControl.CBR;   // constant-quality is meaningless for live
        }
        if (encoder == null) {
            encoder = "";
        }
        if (preset == null) {
            preset = "";
        }
        if (h264Profile == null || h264Profile.isBlank()) {
            h264Profile = "high";
        }
        if (destinations == null) {
            destinations = new ArrayList<>();
        }
        destinations.removeIf(d -> d == null);
        for (Destination destination : destinations) {
            if (destination.platform == null) {
                destination.platform = StreamPlatform.CUSTOM;
            }
            if (destination.ingestUrl == null) {
                destination.ingestUrl = "";
            }
            if (destination.streamKey == null) {
                destination.streamKey = "";
            }
            if (destination.name == null || destination.name.isBlank()) {
                destination.name = destination.platform.displayName();
            }
        }
    }

    /** True when any destination holds a secret, which drives file permissions. */
    public boolean hasSecrets() {
        return destinations.stream().anyMatch(d -> d.streamKey != null && !d.streamKey.isEmpty());
    }
}
