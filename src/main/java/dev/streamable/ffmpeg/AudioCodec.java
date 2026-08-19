package dev.streamable.ffmpeg;

/** Audio codecs for output. AAC is the only universally accepted RTMP choice. */
public enum AudioCodec {
    AAC("aac", "AAC", true),
    OPUS("libopus", "Opus", false),
    FLAC("flac", "FLAC (lossless)", false),
    PCM("pcm_s16le", "PCM (uncompressed)", false);

    private final String ffmpegName;
    private final String displayName;
    private final boolean streamSafe;

    AudioCodec(String ffmpegName, String displayName, boolean streamSafe) {
        this.ffmpegName = ffmpegName;
        this.displayName = displayName;
        this.streamSafe = streamSafe;
    }

    public String ffmpegName() {
        return ffmpegName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isStreamSafe() {
        return streamSafe;
    }
}
