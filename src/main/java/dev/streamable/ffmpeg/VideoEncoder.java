package dev.streamable.ffmpeg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Video encoders Stream-able knows how to drive.
 *
 * <p>Each constant knows the FFmpeg encoder name, which hardware family it
 * belongs to and how to express rate control, because the flag spellings differ
 * per family ({@code -rc cbr} for NVENC, {@code -rc_mode CBR} for AMF,
 * {@code -maxrate/-bufsize} for libx264, and so on).</p>
 *
 * <p>Availability is never assumed from this list - {@link FFmpegCapabilityProbe}
 * runs a real encode test, because FFmpeg happily lists encoders that fail at
 * runtime when the GPU or driver is missing.</p>
 */
public enum VideoEncoder {

    NVENC_H264("h264_nvenc", Family.NVIDIA, Codec.H264, "NVIDIA NVENC H.264"),
    NVENC_HEVC("hevc_nvenc", Family.NVIDIA, Codec.HEVC, "NVIDIA NVENC HEVC"),
    NVENC_AV1("av1_nvenc", Family.NVIDIA, Codec.AV1, "NVIDIA NVENC AV1"),
    QSV_H264("h264_qsv", Family.INTEL, Codec.H264, "Intel Quick Sync H.264"),
    QSV_HEVC("hevc_qsv", Family.INTEL, Codec.HEVC, "Intel Quick Sync HEVC"),
    AMF_H264("h264_amf", Family.AMD, Codec.H264, "AMD AMF H.264"),
    AMF_HEVC("hevc_amf", Family.AMD, Codec.HEVC, "AMD AMF HEVC"),
    VAAPI_H264("h264_vaapi", Family.VAAPI, Codec.H264, "VA-API H.264 (Linux)"),
    X264("libx264", Family.SOFTWARE, Codec.H264, "Software x264"),
    X265("libx265", Family.SOFTWARE, Codec.HEVC, "Software x265"),
    VP9("libvpx-vp9", Family.SOFTWARE, Codec.VP9, "Software VP9"),
    AV1_SVT("libsvtav1", Family.SOFTWARE, Codec.AV1, "Software AV1 (SVT)");

    public enum Family { NVIDIA, INTEL, AMD, VAAPI, SOFTWARE }

    public enum Codec { H264, HEVC, AV1, VP9 }

    private final String ffmpegName;
    private final Family family;
    private final Codec codec;
    private final String displayName;

    VideoEncoder(String ffmpegName, Family family, Codec codec, String displayName) {
        this.ffmpegName = ffmpegName;
        this.family = family;
        this.codec = codec;
        this.displayName = displayName;
    }

    public String ffmpegName() {
        return ffmpegName;
    }

    public Family family() {
        return family;
    }

    public Codec codec() {
        return codec;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isHardware() {
        return family != Family.SOFTWARE;
    }

    /**
     * Whether this encoder is a safe choice for a public livestream.
     *
     * <p>H.264 is the only codec every major RTMP ingest reliably accepts. A GPU
     * being able to encode AV1 says nothing about whether Twitch will take it,
     * so AV1/HEVC/VP9 are offered for recording but flagged here.</p>
     */
    public boolean isStreamSafe() {
        return codec == Codec.H264;
    }

    /**
     * Preference order used when auto-detecting an encoder, best first.
     *
     * <p>Hardware first (it keeps frame times stable, which matters more than
     * raw quality while playing), software x264 last as the universal fallback.</p>
     */
    public static List<VideoEncoder> autoDetectOrder(boolean streamSafeOnly) {
        List<VideoEncoder> order = new ArrayList<>(List.of(
                NVENC_H264, QSV_H264, AMF_H264, VAAPI_H264, X264));
        if (!streamSafeOnly) {
            order.addAll(List.of(NVENC_HEVC, QSV_HEVC, AMF_HEVC, NVENC_AV1, X265, AV1_SVT, VP9));
        }
        return List.copyOf(order);
    }

    public static VideoEncoder byFfmpegName(String name) {
        if (name != null) {
            String lower = name.trim().toLowerCase(Locale.ROOT);
            for (VideoEncoder encoder : values()) {
                if (encoder.ffmpegName.equals(lower)) {
                    return encoder;
                }
            }
        }
        return X264;
    }
}
