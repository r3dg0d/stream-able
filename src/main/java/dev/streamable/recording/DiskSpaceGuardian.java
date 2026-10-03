/*
 * Ported from Record-able by JoEusebe (MIT). See NOTICE for attribution.
 * Adapted for Stream-able: package, logging, plain-language messages, and a
 * pure evaluate() path so thresholds are unit-testable without a FileStore.
 */
package dev.streamable.recording;

import dev.streamable.StreamAbleLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Refuses to start (and can stop) a recording when the output volume is nearly
 * full, so a long encode does not fill the disk and corrupt the last file.
 *
 * <p>Thresholds come from {@link Thresholds} (defaults match the Recording
 * page). Status is decided from usable free bytes versus total capacity; when
 * the store cannot be queried the check fails open so a flaky mount never
 * blocks recording by itself.</p>
 */
public final class DiskSpaceGuardian {

    /**
     * Under this many seconds of recording left → CRITICAL on Stream Health
     * and the Stream HUD {@code DISK LOW} pill.
     */
    public static final long ETA_CRITICAL_SECONDS = 1_800L;
    /**
     * Under this many seconds of recording left → WARNING on Stream Health
     * and the Stream HUD {@code DISK WARN} pill.
     */
    public static final long ETA_WARNING_SECONDS = 7_200L;

    public enum DiskStatus {
        OK,
        WARNING,
        BLOCKED
    }

    public record DiskCheckResult(DiskStatus status, long freeSpaceMB, long totalSpaceMB,
                                  int usedPercent, String message) {
    }

    private DiskSpaceGuardian() {
    }

    /**
     * Pure threshold decision. Prefer this in tests; production calls
     * {@link #check(Path, Thresholds)} which fills the byte counts from the
     * FileStore.
     *
     * @param freeBytes  usable free bytes ({@code < 0} → fail open)
     * @param totalBytes total capacity ({@code <= 0} → fail open)
     */
    public static DiskCheckResult evaluate(long freeBytes, long totalBytes, Thresholds config) {
        Thresholds thresholds = config == null ? Thresholds.DEFAULT : config.clamped();
        if (freeBytes < 0 || totalBytes <= 0) {
            return new DiskCheckResult(DiskStatus.OK, -1, -1, 0,
                    "Could not determine disk space.");
        }
        long totalMB = totalBytes / (1024L * 1024L);
        long freeMB = freeBytes / (1024L * 1024L);
        int usedPercent = (int) Math.min(100, Math.max(0, 100L - (freeBytes * 100L / totalBytes)));

        if (usedPercent >= thresholds.diskSpaceBlockPercent() || freeMB < thresholds.diskSpaceMinFreeMB()) {
            return new DiskCheckResult(DiskStatus.BLOCKED, freeMB, totalMB, usedPercent,
                    String.format(Locale.ROOT,
                            "Disk is %d%% full (%d MB free). Recording blocked to prevent filling the volume.",
                            usedPercent, freeMB));
        }
        if (usedPercent >= thresholds.diskSpaceWarnPercent()) {
            return new DiskCheckResult(DiskStatus.WARNING, freeMB, totalMB, usedPercent,
                    String.format(Locale.ROOT,
                            "Disk is %d%% full (%d MB free). Recording may be cut short.",
                            usedPercent, freeMB));
        }
        return new DiskCheckResult(DiskStatus.OK, freeMB, totalMB, usedPercent,
                String.format(Locale.ROOT, "Disk space OK: %d MB free (%d%% used).", freeMB, usedPercent));
    }

    /**
     * Checks disk space at the given output directory.
     *
     * @param outputDir the directory where recordings are stored
     * @param config    warn / block / min-free thresholds
     */
    /**
     * Checks disk space at {@code outputDir}. The directory must already exist
     * (or its parent must be queryable); this method never creates folders.
     */
    public static DiskCheckResult check(Path outputDir, Thresholds config) {
        try {
            Path probe = outputDir;
            while (probe != null && !Files.exists(probe)) {
                probe = probe.getParent();
            }
            if (probe == null) {
                return evaluate(-1, -1, config);
            }
            java.nio.file.FileStore store = Files.getFileStore(probe);
            return evaluate(store.getUsableSpace(), store.getTotalSpace(), config);
        } catch (Exception e) {
            StreamAbleLog.RECORDING.warn("Failed to check disk space for {}: {}", outputDir, e.getMessage());
            return new DiskCheckResult(DiskStatus.OK, -1, -1, 0,
                    "Could not check disk space: " + e.getMessage());
        }
    }

    /**
     * Estimated seconds of recording left from free space and data rate.
     * Negative when unknown. After 5 s of recording with bytes written, uses the
     * measured rate; otherwise falls back to {@code bitrateKbps * 125} bytes/s
     * (kilobits → bytes). Shared by Stream Health and the Stream HUD disk badge
     * so the thresholds cannot drift.
     */
    public static double secondsRemaining(long freeBytes, long recordingMillis,
                                          long recordingBytes, int bitrateKbps) {
        if (freeBytes <= 0) {
            return -1;
        }
        double bytesPerSecond = recordingMillis > 5_000 && recordingBytes > 0
                ? recordingBytes / (recordingMillis / 1000.0)
                : bitrateKbps * 125.0;
        return bytesPerSecond > 0 ? freeBytes / bytesPerSecond : -1;
    }

    /** Usable free space in MiB, or {@code -1} when unknown. Never creates folders. */
    public static long getFreeSpaceMB(Path path) {
        try {
            Path probe = path;
            while (probe != null && !Files.exists(probe)) {
                probe = probe.getParent();
            }
            if (probe == null) {
                return -1;
            }
            return Files.getFileStore(probe).getUsableSpace() / (1024L * 1024L);
        } catch (Exception e) {
            StreamAbleLog.RECORDING.debug("Could not get free space for {}", path, e);
            return -1;
        }
    }

    /** Human-readable free space for Studio labels. */
    public static String getFormattedFreeSpace(Path path) {
        long freeMB = getFreeSpaceMB(path);
        if (freeMB < 0) {
            return "Unknown";
        }
        if (freeMB >= 1024) {
            return String.format(Locale.ROOT, "%.1f GB", freeMB / 1024.0);
        }
        return freeMB + " MB";
    }

    /**
     * Tooltip for Studio's "Block at used %" field. The free-space half is the
     * configured floor ({@code diskSpaceMinFreeMb}, default 500), clamped the
     * same way {@link Thresholds#clamped()} does, not a hardcoded 100 MB.
     */
    public static String blockAtUsedTooltip(int minFreeMb) {
        return "A recording will not start (and an active one stops) at this used percentage, "
                + "or when fewer than " + configuredMinFreeMb(minFreeMb) + " MB are free.";
    }

    /** Tooltip for Studio's free-space floor field. Same floor as {@link #blockAtUsedTooltip(int)}. */
    public static String stopBelowFreeTooltip(int minFreeMb) {
        return "A recording will not start, and an active one stops, when fewer than "
                + configuredMinFreeMb(minFreeMb) + " MB are free.";
    }

    /** Floor actually enforced: {@code diskSpaceMinFreeMb} after {@link Thresholds#clamped()}. */
    public static int configuredMinFreeMb(int minFreeMb) {
        return new Thresholds(90, 95, minFreeMb).clamped().diskSpaceMinFreeMB();
    }

    /**
     * Disk thresholds. Defaults match Record-able / the Recording page.
     *
     * @param diskSpaceWarnPercent  used-percentage at which to warn
     * @param diskSpaceBlockPercent used-percentage at which to refuse / stop
     * @param diskSpaceMinFreeMB    free space in MiB below which a recording will not start
     *                              (and an active one stops). Default 500; clamped to at least 100.
     */
    public record Thresholds(int diskSpaceWarnPercent, int diskSpaceBlockPercent, int diskSpaceMinFreeMB) {

        public static final Thresholds DEFAULT = new Thresholds(90, 95, 500);

        /** Clamps into a usable range and keeps warn &lt; block. */
        public Thresholds clamped() {
            int block = Math.clamp(diskSpaceBlockPercent, 50, 100);
            int warn = Math.clamp(diskSpaceWarnPercent, 1, block - 1);
            int minFree = Math.clamp(diskSpaceMinFreeMB, 100, 1_000_000);
            return new Thresholds(warn, block, minFree);
        }
    }
}
