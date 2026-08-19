package dev.streamable.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FramePacerTest {

    private static final long MS = 1_000_000L;

    @Test
    @DisplayName("a game rendering faster than the target has renders skipped")
    void skipsWhenGameOutrunsTarget() {
        FramePacer pacer = new FramePacer(60);          // 16.67 ms per frame
        long now = 0;
        pacer.reset(now);
        int emitted = 0;
        // 300 fps of renders for one second.
        for (int i = 0; i < 300; i++) {
            now += 1_000_000_000L / 300;
            emitted += pacer.framesDue(now);
        }
        assertEquals(60, emitted, 1, "one second must produce ~60 frames, not 300");
        assertTrue(pacer.skippedFrames() > 200);
    }

    @Test
    @DisplayName("a game rendering slower than the target gets frames duplicated")
    void duplicatesWhenGameFallsBehind() {
        FramePacer pacer = new FramePacer(60);
        long now = 0;
        pacer.reset(now);
        int emitted = 0;
        // 30 fps of renders for one second.
        for (int i = 0; i < 30; i++) {
            now += 1_000_000_000L / 30;
            emitted += pacer.framesDue(now);
        }
        assertEquals(60, emitted, 2, "one second must still produce ~60 frames");
        assertTrue(pacer.duplicatedFrames() > 0);
    }

    @Test
    void matchingRatesEmitOneFrameEach() {
        FramePacer pacer = new FramePacer(60);
        long now = 0;
        pacer.reset(now);
        int emitted = 0;
        for (int i = 0; i < 60; i++) {
            now += 1_000_000_000L / 60;
            emitted += pacer.framesDue(now);
        }
        assertEquals(60, emitted, 1);
        assertEquals(0, pacer.duplicatedFrames());
    }

    @Test
    @DisplayName("a long stall resynchronises instead of flooding the queue")
    void longStallDoesNotBurst() {
        FramePacer pacer = new FramePacer(60);
        pacer.reset(0);
        // A ten-second freeze would otherwise be worth 600 duplicate frames.
        int due = pacer.framesDue(10_000 * MS);
        assertEquals(1, due, "a long stall must not emit a burst");
    }

    @Test
    void catchUpIsBounded() {
        FramePacer pacer = new FramePacer(60);
        pacer.reset(0);
        // 200 ms gap: 12 frames' worth, but capped.
        assertTrue(pacer.framesDue(200 * MS) <= 3);
    }

    @Test
    void firstCallStartsTheTimeline() {
        FramePacer pacer = new FramePacer(30);
        assertEquals(1, pacer.framesDue(123_456_789L), "the first render always emits");
    }
}
