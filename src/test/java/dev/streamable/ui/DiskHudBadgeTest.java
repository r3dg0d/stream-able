package dev.streamable.ui;

import dev.streamable.recording.DiskSpaceGuardian;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskHudBadgeTest {

    /** 2.5 MB/s measured rate: 1.5 GB in 10 minutes (matches HealthReportTest). */
    private static final long TEN_MIN_MS = 600_000L;
    private static final long ONE_POINT_FIVE_GB = 1_500_000_000L;

    @Test
    void absentWhenNotRecordingOrUnknownFree() {
        assertFalse(DiskHudBadge.of(false, 2_000_000_000L, TEN_MIN_MS, ONE_POINT_FIVE_GB, 40_000).present());
        assertFalse(DiskHudBadge.of(true, -1, TEN_MIN_MS, ONE_POINT_FIVE_GB, 40_000).present());
        assertFalse(DiskHudBadge.of(true, 0, TEN_MIN_MS, ONE_POINT_FIVE_GB, 40_000).present());
        assertEquals(DiskHudBadge.Badge.ABSENT, DiskHudBadge.of(false, 0, 0, 0, 0));
    }

    @Test
    void healthyEtaIsQuiet() {
        // Plenty of free space at the measured 2.5 MB/s → many hours left.
        DiskHudBadge.Badge badge = DiskHudBadge.of(true, 100_000_000_000L, TEN_MIN_MS, ONE_POINT_FIVE_GB, 40_000);
        assertFalse(badge.present());
        assertEquals(DiskHudBadge.Level.NONE, badge.level());
        assertEquals("", badge.label());
    }

    @Test
    void underTwoHoursIsWarningDiskWarn() {
        // 2 GB free @ 2.5 MB/s ≈ 13 minutes... wait that's critical.
        // For WARNING: need between 30 min and 2 h → e.g. 10 GB free ≈ 67 min.
        long free = 10_000_000_000L;
        double eta = DiskSpaceGuardian.secondsRemaining(free, TEN_MIN_MS, ONE_POINT_FIVE_GB, 40_000);
        assertTrue(eta >= DiskSpaceGuardian.ETA_CRITICAL_SECONDS);
        assertTrue(eta < DiskSpaceGuardian.ETA_WARNING_SECONDS);

        DiskHudBadge.Badge badge = DiskHudBadge.of(true, free, TEN_MIN_MS, ONE_POINT_FIVE_GB, 40_000);
        assertTrue(badge.present());
        assertEquals(DiskHudBadge.Level.WARNING, badge.level());
        assertEquals("DISK WARN", badge.label());
    }

    @Test
    void underThirtyMinutesIsCriticalDiskLow() {
        // 2 GB free @ 2.5 MB/s ≈ 13 minutes (HealthReportTest.diskSpaceWarning).
        DiskHudBadge.Badge badge = DiskHudBadge.of(true, 2_000_000_000L, TEN_MIN_MS, ONE_POINT_FIVE_GB, 40_000);
        assertTrue(badge.present());
        assertEquals(DiskHudBadge.Level.CRITICAL, badge.level());
        assertEquals("DISK LOW", badge.label());
    }

    @Test
    void earlySessionFallsBackToConfiguredBitrate() {
        // No measured bytes yet: 6000 kbps → 750_000 B/s; 1 GB free ≈ 23 min → DISK LOW.
        DiskHudBadge.Badge badge = DiskHudBadge.of(true, 1_000_000_000L, 1_000L, 0, 6_000);
        assertEquals("DISK LOW", badge.label());
        assertEquals(DiskHudBadge.Level.CRITICAL, badge.level());
    }
}
