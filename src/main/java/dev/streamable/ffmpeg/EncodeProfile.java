package dev.streamable.ffmpeg;

/**
 * A complete video+audio encode configuration.
 *
 * <p>This record is the <em>grouping key</em> for multistreaming. Destinations
 * with an equal profile share one FFmpeg process and one encode; destinations
 * with different profiles necessarily need their own encoder, which costs extra
 * CPU/GPU and is surfaced to the user as a warning.</p>
 */
public record EncodeProfile(VideoProfile video, AudioProfile audio) {

    public long estimatedBitsPerSecond() {
        return video.estimatedBitsPerSecond() + audio.estimatedBitsPerSecond();
    }
}
