package dev.streamable.streaming;

import java.util.List;
import java.util.Locale;

/**
 * Immutable snapshot of how the broadcast is doing.
 *
 * <p>Published by {@code StreamController} and consumed by the HUD and the
 * Studio screen. A snapshot rather than live getters so the renderer sees a
 * consistent set of numbers within one frame.</p>
 *
 * @param live               whether any destination is publishing
 * @param uptimeMillis       time since the session started
 * @param videoBitrateKbps   configured video bitrate
 * @param fps                configured output frame rate
 * @param framesSubmitted    frames handed to the encoder
 * @param framesDropped      frames discarded because the pipeline was saturated
 * @param queuePressure      encoder queue fullness in {@code [0,1]}
 * @param encoderName        human-readable encoder in use
 * @param destinations       per-destination status lines
 */
public record StreamHealth(
        boolean live,
        long uptimeMillis,
        int videoBitrateKbps,
        int fps,
        long framesSubmitted,
        long framesDropped,
        double queuePressure,
        String encoderName,
        List<DestinationStatus> destinations,
        double outputKbps,
        double encodeFps,
        double encodeLatencyMillis,
        double encodeSpeed,
        long framesRepeatedForTiming,
        int reconnects,
        int audioBitrateKbps,
        String outputResolution) {

    public static final StreamHealth OFFLINE = new StreamHealth(
            false, 0, 0, 0, 0, 0, 0, "", List.of(), -1, -1, -1, -1, 0, 0, 0, "");

    /** One row of the health HUD. */
    public record DestinationStatus(String name, DestinationState state, String detail) {
    }

    /** Formats uptime as {@code HH:MM:SS}. */
    public String formattedUptime() {
        long totalSeconds = uptimeMillis / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d",
                totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
    }

    /** True when the encoder is falling behind badly enough to tell the user. */
    public boolean isEncoderOverloaded() {
        return queuePressure >= 0.8;
    }

    public String queuePressurePercent() {
        return Math.round(queuePressure * 100) + "%";
    }

    public int liveDestinationCount() {
        return (int) destinations.stream().filter(d -> d.state() == DestinationState.LIVE).count();
    }
}
