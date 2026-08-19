package dev.streamable.streaming;

import dev.streamable.ffmpeg.EncodeProfile;

import java.util.List;
import java.util.Locale;

/**
 * Estimates the upload capacity a multistream session needs.
 *
 * <p>Sharing an encoder across destinations saves CPU/GPU, but it does
 * <em>not</em> save bandwidth: each destination is a separate TCP connection
 * carrying its own copy of the same packets. Three 12 Mbps outputs still need
 * roughly 36 Mbps of upstream, which surprises people often enough to be worth
 * showing explicitly.</p>
 */
public final class BandwidthEstimator {

    /** Head-room factor recommended on top of the raw requirement. */
    public static final double RECOMMENDED_HEADROOM = 1.25;

    private BandwidthEstimator() {
    }

    /**
     * @param groups one entry per encoder group; each carries its profile and
     *               the number of destinations fed from it
     * @return the estimate for the whole session
     */
    public static Estimate estimate(List<GroupLoad> groups) {
        long videoBits = 0;
        long audioBits = 0;
        int destinations = 0;
        for (GroupLoad group : groups) {
            int count = Math.max(0, group.destinationCount());
            videoBits += group.profile().video().estimatedBitsPerSecond() * count;
            audioBits += group.profile().audio().estimatedBitsPerSecond() * count;
            destinations += count;
        }
        return new Estimate(videoBits, audioBits, destinations, groups.size());
    }

    /** One encoder group and how many destinations it feeds. */
    public record GroupLoad(EncodeProfile profile, int destinationCount) {
    }

    /**
     * @param videoBitsPerSecond total video upload across all destinations
     * @param audioBitsPerSecond total audio upload across all destinations
     * @param destinationCount   number of live destinations
     * @param encoderCount       number of distinct encoders running
     */
    public record Estimate(long videoBitsPerSecond, long audioBitsPerSecond,
                           int destinationCount, int encoderCount) {

        public long totalBitsPerSecond() {
            return videoBitsPerSecond + audioBitsPerSecond;
        }

        public double totalMbps() {
            return totalBitsPerSecond() / 1_000_000.0;
        }

        public double recommendedUplinkMbps() {
            return totalMbps() * RECOMMENDED_HEADROOM;
        }

        /** Multi-line summary for the Destinations screen. */
        public String describe() {
            return String.format(Locale.ROOT,
                    """
                    Estimated upload requirement:
                    Video: %.1f Mbps
                    Audio: %.1f Mbps
                    Destinations: %d
                    Total: ~%.1f Mbps
                    Recommended connection: >= %.0f Mbps stable upload""",
                    videoBitsPerSecond / 1_000_000.0,
                    audioBitsPerSecond / 1_000_000.0,
                    destinationCount,
                    totalMbps(),
                    Math.ceil(recommendedUplinkMbps()));
        }
    }
}
