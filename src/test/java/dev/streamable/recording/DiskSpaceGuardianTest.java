package dev.streamable.recording;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void warnAtMinFreeFloor() {
        // 50% used but only 400 MB free → warn via min-free
        DiskSpaceGuardian.DiskCheckResult result = DiskSpaceGuardian.evaluate(mb(400), gb(1), THRESHOLDS);
        assertEquals(DiskSpaceGuardian.DiskStatus.WARNING, result.status());
        assertEquals(400, result.freeSpaceMB());
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
        // 50 MB free of 200 MB → 75% used (below warn/block %) but under the 100 MB hard floor
        DiskSpaceGuardian.DiskCheckResult result = DiskSpaceGuardian.evaluate(mb(50), mb(200), THRESHOLDS);
        assertEquals(DiskSpaceGuardian.DiskStatus.BLOCKED, result.status());
        assertEquals(50, result.freeSpaceMB());
        assertTrue(result.usedPercent() < 90, "blocked by hard free floor, not used %");
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
}
