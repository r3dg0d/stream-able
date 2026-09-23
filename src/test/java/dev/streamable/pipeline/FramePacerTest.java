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
    @DisplayName("a stall is covered by repeats so video never falls behind audio")
    void stallPreservesTheTimeline() {
        FramePacer pacer = new FramePacer(60);
        assertEquals(1, pacer.framesDue(0));
        // A two-second freeze (world load): 120 frames are owed and reported,
        // rather than silently discarded as the old pacer did.
        int due = pacer.framesDue(2_000 * MS);
        assertEquals(120, due);
        assertEquals(121, pacer.emittedFrames());
        assertEquals(120, pacer.largestCatchUp());
    }

    @Test
    @DisplayName("180 Hz game into a 60 FPS output: exact count over 30 minutes")
    void highRefreshGameDoesNotDriftOverLongSessions() {
        FramePacer pacer = new FramePacer(60);
        long frameCount = 0;
        long renders = 180L * 60 * 30;                  // 30 minutes at 180 Hz
        for (long i = 0; i <= renders; i++) {
            long now = i * 1_000_000_000L / 180;          // exact 180 Hz clock
            frameCount += pacer.framesDue(now);
        }
        assertEquals(60L * 60 * 30 + 1, frameCount, "one frame per 1/60 s, plus the frame at t=0");
        assertEquals(0, pacer.duplicatedFrames(), "a faster game never needs repeats");
    }

    @Test
    void jitteryRendersStillProduceTheExactCount() {
        FramePacer pacer = new FramePacer(60);
        java.util.Random random = new java.util.Random(7);
        long now = 0;
        long frames = pacer.framesDue(now);
        while (now < 600_000 * MS) {                     // ten minutes
            now += (2 + random.nextInt(40)) * MS;         // 2..41 ms between renders
            frames += pacer.framesDue(now);
        }
        assertEquals(now * 60 / 1_000_000_000L + 1, frames);
    }

    @Test
    void rendersExactlyOnDeadlinesEmitNoSpuriousDuplicates() {
        FramePacer pacer = new FramePacer(60);
        for (long i = 0; i < 6000; i++) {
            assertEquals(1, pacer.framesDue(pacer.deadlineOffsetNanos(i)), "frame " + i);
        }
        assertEquals(0, pacer.duplicatedFrames());
    }

    @Test
    void firstCallStartsTheTimeline() {
        FramePacer pacer = new FramePacer(30);
        assertEquals(1, pacer.framesDue(123_456_789L), "the first render always emits");
    }
}
