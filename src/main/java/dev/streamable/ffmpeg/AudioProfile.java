package dev.streamable.ffmpeg;

/**
 * One audio encode configuration.
 *
 * @param codec         output codec
 * @param bitrateKbps   target bitrate (ignored for lossless codecs)
 * @param sampleRate    output sample rate in Hz
 * @param channels      channel count (2 = stereo program mix)
 */
public record AudioProfile(AudioCodec codec, int bitrateKbps, int sampleRate, int channels) {

    /** What virtually every RTMP ingest expects. */
    public static final AudioProfile LIVE_DEFAULT = new AudioProfile(AudioCodec.AAC, 160, 48_000, 2);

    public AudioProfile {
        codec = codec == null ? AudioCodec.AAC : codec;
        bitrateKbps = Math.clamp(bitrateKbps, 32, 1024);
        sampleRate = switch (sampleRate) {
            case 22_050, 32_000, 44_100, 48_000, 96_000 -> sampleRate;
            default -> 48_000;
        };
        channels = Math.clamp(channels, 1, 2);
    }

    public long estimatedBitsPerSecond() {
        return bitrateKbps * 1000L;
    }
}
