package dev.streamable.recording.replay;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipTriggersTest {

    private static final long S = 1_000_000_000L;
    private static final Set<ClipTriggers.Reason> ALL = EnumSet.allOf(ClipTriggers.Reason.class);

    private static ClipTriggers.Observation alive(String dim) {
        return new ClipTriggers.Observation(true, false, dim, false);
    }

    @Test
    void deathSavesAfterTheAftermath() {
        ClipTriggers t = new ClipTriggers();
        assertTrue(t.tick(0, alive("overworld"), ALL, false).isEmpty());
        assertTrue(t.tick(1 * S, new ClipTriggers.Observation(true, true, "overworld", false), ALL, false).isEmpty());
        assertTrue(t.tick(3 * S, new ClipTriggers.Observation(true, true, "overworld", false), ALL, false).isEmpty(),
                "still dead is not a second death");
        assertEquals(Set.of(ClipTriggers.Reason.DEATH),
                t.tick(5 * S, new ClipTriggers.Observation(true, true, "overworld", false), ALL, false));
    }

    @Test
    void nearbyTriggersJoinOneClipAndCooldownApplies() {
        ClipTriggers t = new ClipTriggers();
        t.tick(0, alive("overworld"), ALL, false);
        t.tick(1 * S, new ClipTriggers.Observation(true, false, "overworld", true), ALL, false);
        t.tick(2 * S, alive("overworld"), ALL, true);
        Set<ClipTriggers.Reason> saved = t.tick(5 * S, alive("overworld"), ALL, false);
        assertEquals(EnumSet.of(ClipTriggers.Reason.KILL, ClipTriggers.Reason.ADVANCEMENT), saved);
        assertEquals("kill-advancement", ClipTriggers.tag(saved));
        t.tick(8 * S, new ClipTriggers.Observation(true, false, "overworld", true), ALL, false);
        assertTrue(t.tick(13 * S, alive("overworld"), ALL, false).isEmpty(), "inside the cooldown");
    }

    @Test
    void dimensionChangeCountsButJoiningAWorldDoesNot() {
        ClipTriggers t = new ClipTriggers();
        t.tick(0, alive("overworld"), ALL, false);
        t.tick(1 * S, alive("the_nether"), ALL, false);
        assertEquals(Set.of(ClipTriggers.Reason.DIMENSION), t.tick(5 * S, alive("the_nether"), ALL, false));
        t.tick(6 * S, new ClipTriggers.Observation(false, false, null, false), ALL, false);
        t.tick(30 * S, alive("the_end"), ALL, false);
        assertTrue(t.tick(40 * S, alive("the_end"), ALL, false).isEmpty(), "first dimension after joining");
    }

    @Test
    void disabledReasonsAreIgnored() {
        ClipTriggers t = new ClipTriggers();
        t.tick(0, alive("overworld"), EnumSet.of(ClipTriggers.Reason.KILL), false);
        t.tick(1 * S, new ClipTriggers.Observation(true, true, "overworld", false), EnumSet.of(ClipTriggers.Reason.KILL), true);
        assertTrue(t.tick(9 * S, alive("overworld"), EnumSet.of(ClipTriggers.Reason.KILL), false).isEmpty());
    }
}
