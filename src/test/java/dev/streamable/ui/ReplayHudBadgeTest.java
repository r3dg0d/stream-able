package dev.streamable.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayHudBadgeTest {

    @Test
    void absentWhenBufferOff() {
        assertFalse(ReplayHudBadge.of(false, false, 30, 60).present());
        assertFalse(ReplayHudBadge.of(false, true, 60, 60).present());
        assertEquals(ReplayHudBadge.Badge.ABSENT, ReplayHudBadge.of(false, false, 0, 60));
    }

    @Test
    void fillPercentClampsAndHandlesHostileInputs() {
        assertEquals(0, ReplayHudBadge.fillPercent(0, 60));
        assertEquals(0, ReplayHudBadge.fillPercent(-5, 60));
        assertEquals(0, ReplayHudBadge.fillPercent(Double.NaN, 60));
        assertEquals(0, ReplayHudBadge.fillPercent(30, 0));
        assertEquals(0, ReplayHudBadge.fillPercent(30, -10));
        assertEquals(50, ReplayHudBadge.fillPercent(30, 60));
        assertEquals(100, ReplayHudBadge.fillPercent(60, 60));
        assertEquals(100, ReplayHudBadge.fillPercent(90, 60), "never report more than 100%");
        assertEquals(1, ReplayHudBadge.fillPercent(0.3, 60));
    }

    @Test
    void fillingShowsPercentPill() {
        ReplayHudBadge.Badge badge = ReplayHudBadge.of(true, false, 24, 60);
        assertTrue(badge.present());
        assertEquals(ReplayHudBadge.Level.FILLING, badge.level());
        assertEquals("REPLAY 40%", badge.label());
        assertEquals(40, badge.fillPercent());
    }

    @Test
    void emptyBufferShowsZeroPercent() {
        ReplayHudBadge.Badge badge = ReplayHudBadge.of(true, false, 0, 60);
        assertTrue(badge.present());
        assertEquals(ReplayHudBadge.Level.FILLING, badge.level());
        assertEquals("REPLAY 0%", badge.label());
        assertEquals(0, badge.fillPercent());
    }

    @Test
    void fullBufferShowsHundredPercent() {
        ReplayHudBadge.Badge badge = ReplayHudBadge.of(true, false, 60, 60);
        assertTrue(badge.present());
        assertEquals(ReplayHudBadge.Level.FULL, badge.level());
        assertEquals("REPLAY 100%", badge.label());
        assertEquals(100, badge.fillPercent());
    }

    @Test
    void savingOverridesFillLabel() {
        ReplayHudBadge.Badge badge = ReplayHudBadge.of(true, true, 45, 60);
        assertTrue(badge.present());
        assertEquals(ReplayHudBadge.Level.SAVING, badge.level());
        assertEquals("SAVING", badge.label());
        assertEquals(75, badge.fillPercent(), "fill percent still reported while saving");
    }

    @Test
    void detailLineShowsBufferedOverConfigured() {
        assertEquals("Replay 24 / 60 s", ReplayHudBadge.detailLine(24, 60, false));
        assertEquals("Replay 60 / 60 s", ReplayHudBadge.detailLine(60, 60, false));
        assertEquals("Replay 60 / 60 s · saving", ReplayHudBadge.detailLine(90, 60, true),
                "buffered is clamped to configured; saving suffix appended");
    }

    @Test
    void detailLineHandlesHostileInputs() {
        assertEquals("Replay —", ReplayHudBadge.detailLine(Double.NaN, 60, false));
        assertEquals("Replay · saving", ReplayHudBadge.detailLine(10, 0, true));
        assertEquals("Replay 0 / 60 s", ReplayHudBadge.detailLine(-5, 60, false));
    }
}
