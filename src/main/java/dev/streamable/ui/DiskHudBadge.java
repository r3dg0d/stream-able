package dev.streamable.ui;

import dev.streamable.recording.DiskSpaceGuardian;

/**
 * Compact Stream HUD badge for recording disk pressure.
 *
 * <p>Uses the same ETA math and thresholds as Stream Health
 * ({@link DiskSpaceGuardian#secondsRemaining} /
 * {@link DiskSpaceGuardian#ETA_CRITICAL_SECONDS} /
 * {@link DiskSpaceGuardian#ETA_WARNING_SECONDS}): under 30 minutes left
 * surfaces a critical {@code DISK LOW} pill; under 2 hours a warning
 * {@code DISK WARN} pill. Pure inputs so the badge can be unit-tested
 * without the Minecraft client or a real FileStore.</p>
 */
public final class DiskHudBadge {

    public enum Level {
        NONE,
        WARNING,
        CRITICAL
    }

    /**
     * @param label short pill text ({@code DISK WARN} / {@code DISK LOW}), empty when absent
     * @param level severity for colouring the pill
     */
    public record Badge(String label, Level level) {
        public static final Badge ABSENT = new Badge("", Level.NONE);

        public boolean present() {
            return level != Level.NONE;
        }
    }

    private DiskHudBadge() {
    }

    /**
     * Resolves the compact HUD pill for the current recording disk ETA.
     *
     * @param recording      a recording is active
     * @param freeDiskBytes  usable free bytes on the recordings volume ({@code <= 0} → absent)
     * @param recordingMillis elapsed recording time
     * @param recordingBytes bytes written so far (0 early in the session)
     * @param bitrateKbps    configured recording bitrate (fallback rate)
     */
    public static Badge of(boolean recording, long freeDiskBytes, long recordingMillis,
                           long recordingBytes, int bitrateKbps) {
        if (!recording) {
            return Badge.ABSENT;
        }
        double secondsLeft = DiskSpaceGuardian.secondsRemaining(
                freeDiskBytes, recordingMillis, recordingBytes, bitrateKbps);
        if (secondsLeft < 0) {
            return Badge.ABSENT;
        }
        if (secondsLeft < DiskSpaceGuardian.ETA_CRITICAL_SECONDS) {
            return new Badge("DISK LOW", Level.CRITICAL);
        }
        if (secondsLeft < DiskSpaceGuardian.ETA_WARNING_SECONDS) {
            return new Badge("DISK WARN", Level.WARNING);
        }
        return Badge.ABSENT;
    }
}
