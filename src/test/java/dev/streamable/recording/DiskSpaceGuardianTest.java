package dev.streamable.recording;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskSpaceGuardianTest {

    private static final DiskSpaceGuardian.Thresholds THRESHOLDS =
            new DiskSpaceGuardian.Thresholds(90, 95, 500);

    private static long gb(double value) {
        return (long) (value * 1024L * 1024L * 1024L);
    }

    private static long mb(long value) {
        return value * 1024L * 1024L;
    }

    @Test
    void healthyVolumeIsOk() {
        // 40% used of 100 GB → 60 GB free
        DiskSpaceGuardian.DiskCheckResult result = DiskSpaceGuardian.evaluate(gb(60), gb(100), THRESHOLDS);
        assertEquals(DiskSpaceGuardian.DiskStatus.OK, result.status());
        assertEquals(60 * 1024, result.freeSpaceMB());
        assertTrue(result.message().startsWith("Disk space OK:"));
    }

    @Test
    void warnAtUsedPercent() {
        // 91% used of 100 GB → 9 GB free (still above 500 MB floor)
        DiskSpaceGuardian.DiskCheckResult result = DiskSpaceGuardian.evaluate(gb(9), gb(100), THRESHOLDS);
        assertEquals(DiskSpaceGuardian.DiskStatus.WARNING, result.status());
        assertEquals(91, result.usedPercent());
        assertTrue(result.message().contains("Recording may be cut short"));
    }

    @Test
    void blockBelowConfiguredMinFree() {
        // 400 MB free of 1 GB is under the 500 MB floor but not a used-% block.
        DiskSpaceGuardian.DiskCheckResult result = DiskSpaceGuardian.evaluate(mb(400), gb(1), THRESHOLDS);
        assertEquals(DiskSpaceGuardian.DiskStatus.BLOCKED, result.status());
        assertEquals(400, result.freeSpaceMB());
        assertTrue(result.usedPercent() < 90);
    }

    @Test
    void aboveOldHardFloorStillBlocksWhenConfiguredFloorIsHigher() {
        // 200 MB free of 400 MB is 50% used: above the old hardcoded 100 MB stop,
        // below the configured 500 MB floor, and not a used-% block.
        DiskSpaceGuardian.DiskCheckResult result = DiskSpaceGuardian.evaluate(mb(200), mb(400), THRESHOLDS);
        assertEquals(DiskSpaceGuardian.DiskStatus.BLOCKED, result.status());
        assertEquals(50, result.usedPercent());
    }

    @Test
    void freeAboveConfiguredFloorIsNotBlocked() {
        DiskSpaceGuardian.Thresholds floor = new DiskSpaceGuardian.Thresholds(90, 95, 100);
        // 150 MB free of 300 MB is 50% used and above a 100 MB floor.
        DiskSpaceGuardian.DiskCheckResult result = DiskSpaceGuardian.evaluate(mb(150), mb(300), floor);
        assertEquals(DiskSpaceGuardian.DiskStatus.OK, result.status());
        assertEquals(150, result.freeSpaceMB());
    }

    @Test
    void blockAtUsedPercent() {
        DiskSpaceGuardian.DiskCheckResult result = DiskSpaceGuardian.evaluate(gb(4), gb(100), THRESHOLDS);
        assertEquals(DiskSpaceGuardian.DiskStatus.BLOCKED, result.status());
        assertEquals(96, result.usedPercent());
        assertTrue(result.message().contains("Recording blocked"));
        assertTrue(result.message().chars().noneMatch(c -> c == '\u00a7'), "no Minecraft formatting codes");
    }

    @Test
    void blockAtHardFreeFloor() {
        // 50 MB free of 200 MB → 75% used (below warn/block %) but under the configured floor
        DiskSpaceGuardian.DiskCheckResult result = DiskSpaceGuardian.evaluate(mb(50), mb(200), THRESHOLDS);
        assertEquals(DiskSpaceGuardian.DiskStatus.BLOCKED, result.status());
        assertEquals(50, result.freeSpaceMB());
        assertTrue(result.usedPercent() < 90, "blocked by free-space floor, not used %");
    }

    @Test
    void blockTooltipUsesConfiguredFloorNotStale100() {
        assertEquals(500, DiskSpaceGuardian.Thresholds.DEFAULT.diskSpaceMinFreeMB());
        String atDefault = DiskSpaceGuardian.blockAtUsedTooltip(500);
        assertEquals("A recording will not start (and an active one stops) at this used percentage, "
                + "or when fewer than 500 MB are free.", atDefault);
        assertFalse(atDefault.contains("100 MB"));
        assertEquals("A recording will not start (and an active one stops) at this used percentage, "
                + "or when fewer than 750 MB are free.", DiskSpaceGuardian.blockAtUsedTooltip(750));
        // The field cannot go below 100; the tooltip must show that enforced floor, not the raw input.
        assertTrue(DiskSpaceGuardian.blockAtUsedTooltip(10).contains("fewer than 100 MB"));
        assertEquals("A recording will not start, and an active one stops, when fewer than 500 MB are free.",
                DiskSpaceGuardian.stopBelowFreeTooltip(500));
        assertEquals(500, DiskSpaceGuardian.configuredMinFreeMb(500));
        assertEquals(100, DiskSpaceGuardian.configuredMinFreeMb(10));
    }

    @Test
    void unknownStoreFailsOpen() {
        DiskSpaceGuardian.DiskCheckResult result = DiskSpaceGuardian.evaluate(-1, 0, THRESHOLDS);
        assertEquals(DiskSpaceGuardian.DiskStatus.OK, result.status());
        assertEquals(-1, result.freeSpaceMB());
        assertTrue(result.message().contains("Could not determine"));
    }

    @Test
    void thresholdsClampWarnBelowBlock() {
        DiskSpaceGuardian.Thresholds clamped = new DiskSpaceGuardian.Thresholds(99, 90, 10).clamped();
        assertTrue(clamped.diskSpaceWarnPercent() < clamped.diskSpaceBlockPercent());
        assertEquals(90, clamped.diskSpaceBlockPercent());
        assertEquals(100, clamped.diskSpaceMinFreeMB());
    }

    @Test
    void messagesHaveNoSectionSignOrEmoji() {
        DiskSpaceGuardian.DiskCheckResult blocked = DiskSpaceGuardian.evaluate(mb(50), mb(200), THRESHOLDS);
        DiskSpaceGuardian.DiskCheckResult warn = DiskSpaceGuardian.evaluate(gb(9), gb(100), THRESHOLDS);
        DiskSpaceGuardian.DiskCheckResult ok = DiskSpaceGuardian.evaluate(gb(60), gb(100), THRESHOLDS);
        for (DiskSpaceGuardian.DiskCheckResult result : new DiskSpaceGuardian.DiskCheckResult[]{blocked, warn, ok}) {
            assertTrue(result.message().indexOf('\u00a7') < 0);
            assertTrue(result.message().codePoints().noneMatch(cp -> cp > 0x7f),
                    "messages stay ASCII plain language: " + result.message());
        }
    }

    @Test
    void secondsRemainingUsesMeasuredRateAfterWarmup() {
        // 1.5 GB in 10 min = 2.5 MB/s; 2 GB free ≈ 800 s.
        double eta = DiskSpaceGuardian.secondsRemaining(
                2_000_000_000L, 600_000L, 1_500_000_000L, 40_000);
        assertTrue(eta > 750 && eta < 850, "eta was " + eta);
        assertTrue(eta < DiskSpaceGuardian.ETA_CRITICAL_SECONDS);
    }

    @Test
    void secondsRemainingFallsBackToBitrateWhenCold() {
        // 6000 kbps = 750_000 B/s; 3.375 GB free = 4_500 s (between 30 min and 2 h).
        double eta = DiskSpaceGuardian.secondsRemaining(3_375_000_000L, 1_000L, 0, 6_000);
        assertEquals(4_500.0, eta, 0.1);
        assertTrue(eta >= DiskSpaceGuardian.ETA_CRITICAL_SECONDS);
        assertTrue(eta < DiskSpaceGuardian.ETA_WARNING_SECONDS);
    }

    @Test
    void secondsRemainingUnknownWhenFreeUnknown() {
        assertEquals(-1, DiskSpaceGuardian.secondsRemaining(-1, 60_000, 1_000_000, 6_000));
        assertEquals(-1, DiskSpaceGuardian.secondsRemaining(0, 60_000, 1_000_000, 6_000));
    }
}
