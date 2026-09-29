package dev.streamable.ui;

import dev.streamable.audio.mic.MicrophoneProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicHudBadgeTest {

    private static MicrophoneProcessor.Stats stats(int backlog, long dropped, long overruns, boolean overloaded) {
        return new MicrophoneProcessor.Stats(2.0, 3.0, 40.0, backlog, dropped, overruns, 0, 1000, overloaded);
    }

    @Test
    void absentWhenCaptureOffOrNotCapturing() {
        assertFalse(MicHudBadge.of(false, true, stats(8, 0, 1, true)).present());
        assertFalse(MicHudBadge.of(true, false, stats(8, 0, 1, true)).present());
        assertFalse(MicHudBadge.of(true, true, null).present());
        assertEquals(MicHudBadge.Badge.ABSENT, MicHudBadge.of(true, false, null));
    }

    @Test
    void healthyMicIsQuiet() {
        MicHudBadge.Badge badge = MicHudBadge.of(true, true, stats(0, 0, 0, false));
        assertFalse(badge.present());
        assertEquals(MicHudBadge.Level.NONE, badge.level());
        assertEquals("", badge.label());
    }

    @Test
    void backlogAboveThresholdIsWarningMicDsp() {
        MicHudBadge.Badge badge = MicHudBadge.of(true, true, stats(MicHudBadge.BACKLOG_WARN_BLOCKS + 1, 0, 1, false));
        assertTrue(badge.present());
        assertEquals(MicHudBadge.Level.WARNING, badge.level());
        assertEquals("MIC DSP", badge.label());
    }

    @Test
    void backlogAtThresholdAloneIsQuietUnlessOverloaded() {
        assertFalse(MicHudBadge.of(true, true, stats(MicHudBadge.BACKLOG_WARN_BLOCKS, 0, 0, false)).present());
        MicHudBadge.Badge overloaded = MicHudBadge.of(true, true, stats(1, 0, 1, true));
        assertTrue(overloaded.present());
        assertEquals("MIC DSP", overloaded.label());
        assertEquals(MicHudBadge.Level.WARNING, overloaded.level());
    }

    @Test
    void dropsEscalateToCriticalMicDrop() {
        MicHudBadge.Badge badge = MicHudBadge.of(true, true, stats(8, 12, 3, true));
        assertTrue(badge.present());
        assertEquals(MicHudBadge.Level.CRITICAL, badge.level());
        assertEquals("MIC DROP", badge.label());
    }

    @Test
    void meterCaptionPrefersMuteThenPressure() {
        MicHudBadge.Badge warning = new MicHudBadge.Badge("MIC DSP", MicHudBadge.Level.WARNING);
        MicHudBadge.Badge critical = new MicHudBadge.Badge("MIC DROP", MicHudBadge.Level.CRITICAL);
        assertEquals("MIC MUTED", MicHudBadge.meterCaption(true, warning));
        assertEquals("MIC MUTED", MicHudBadge.meterCaption(true, MicHudBadge.Badge.ABSENT));
        assertEquals("MIC DROP", MicHudBadge.meterCaption(false, critical));
        assertEquals("MIC DSP", MicHudBadge.meterCaption(false, warning));
        assertEquals("MIC", MicHudBadge.meterCaption(false, MicHudBadge.Badge.ABSENT));
    }
}
