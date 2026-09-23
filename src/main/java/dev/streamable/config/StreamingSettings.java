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
    public boolean reconnect = true;
    public long reconnectDelayMs = 5_000;
    public long maxReconnectDelayMs = 60_000;
    public int maxReconnectAttempts = 10;

    /** Bounded frame queue depth before frames start being dropped. */
    public int frameQueueCapacity = 90;

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
        maxReconnectAttempts = Math.clamp(maxReconnectAttempts, 0, 1000);
        reconnectDelayMs = Math.clamp(reconnectDelayMs, 250, 300_000);
        maxReconnectDelayMs = Math.clamp(maxReconnectDelayMs, reconnectDelayMs, 900_000);
        frameQueueCapacity = Math.clamp(frameQueueCapacity, 8, 600);
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
