package dev.streamable.ffmpeg;

/**
 * Everything needed to configure one video encode.
 *
 * <p>Used as part of the multistream grouping key: destinations that share an
 * identical {@link EncodeProfile} are served by a single encoder and fanned out
 * with FFmpeg's {@code tee} muxer, so three services cost one encode.</p>
 *
 * @param encoder            the FFmpeg encoder to drive
 * @param width              output width in pixels (even)
 * @param height             output height in pixels (even)
 * @param fps                output frame rate
 * @param rateControl        rate-control strategy
 * @param bitrateKbps        target bitrate
 * @param maxBitrateKbps     ceiling; {@code <= 0} means "same as target"
 * @param bufferSizeKbits    rate-control buffer; {@code <= 0} derives 2x bitrate
 * @param keyframeSeconds    keyframe interval in seconds (2 for live services)
 * @param preset             encoder preset name, or blank for the encoder default
 * @param h264Profile        H.264 profile (baseline/main/high), or blank
 * @param bFrames            number of B-frames; {@code 0} is safest for low latency
 */
public record VideoProfile(
        VideoEncoder encoder,
        int width,
        int height,
        int fps,
        RateControl rateControl,
        int bitrateKbps,
        int maxBitrateKbps,
        int bufferSizeKbits,
        double keyframeSeconds,
        String preset,
        String h264Profile,
        int bFrames) {

    public VideoProfile {
        // Most encoders reject odd dimensions with yuv420p chroma subsampling.
        width = Math.clamp(width - (width % 2), 16, 16384);
        height = Math.clamp(height - (height % 2), 16, 16384);
        fps = Math.clamp(fps, 1, 480);
        bitrateKbps = Math.clamp(bitrateKbps, 100, 200_000);
        maxBitrateKbps = maxBitrateKbps <= 0 ? bitrateKbps : Math.clamp(maxBitrateKbps, 100, 400_000);
        bufferSizeKbits = bufferSizeKbits <= 0 ? bitrateKbps * 2 : Math.clamp(bufferSizeKbits, 100, 800_000);
        keyframeSeconds = keyframeSeconds <= 0 ? 2.0 : Math.clamp(keyframeSeconds, 0.5, 10.0);
        preset = preset == null ? "" : preset.trim();
        h264Profile = h264Profile == null ? "" : h264Profile.trim();
        bFrames = Math.clamp(bFrames, 0, 8);
        encoder = encoder == null ? VideoEncoder.X264 : encoder;
        rateControl = rateControl == null ? RateControl.CBR : rateControl;
    }

    /**
     * The broadly compatible livestream default: H.264, CBR, 2-second keyframes.
     * Hardware encoding is still used when available - only the codec and the
     * GOP length are pinned, because those are what ingests actually require.
     */
    public static VideoProfile liveDefault(VideoEncoder encoder, int width, int height, int fps, int bitrateKbps) {
        return new VideoProfile(encoder, width, height, fps, RateControl.CBR,
                bitrateKbps, bitrateKbps, bitrateKbps * 2, 2.0,
                defaultPresetFor(encoder), "high", 0);
    }

    /** Keyframe interval expressed in frames, which is what {@code -g} takes. */
    public int keyframeIntervalFrames() {
        return Math.max(1, (int) Math.round(fps * keyframeSeconds));
    }

    /** A sensible low-latency preset name per encoder family. */
    public static String defaultPresetFor(VideoEncoder encoder) {
        return switch (encoder.family()) {
            case NVIDIA -> "p5";
            case AMD -> "balanced";
            case INTEL -> "veryfast";
            case VAAPI -> "";
            case SOFTWARE -> "veryfast";
        };
    }

    public long estimatedBitsPerSecond() {
        return bitrateKbps * 1000L;
    }
}
