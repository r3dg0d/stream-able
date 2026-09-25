package dev.streamable.recording.replay;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Decides when to save automatic clips from the replay buffer.
 *
 * <p>Pure logic, fed game observations by the client tick, so it can be
 * tested without a world. A trigger schedules a save a few seconds later, so
 * the clip includes the aftermath (the death screen, the advancement toast).
 * Triggers that arrive while a save is pending join it instead of producing
 * overlapping clips, and after a save there is a short cooldown.</p>
 */
public final class ClipTriggers {

    public enum Reason {
        DEATH("death"), KILL("kill"), ADVANCEMENT("advancement"), DIMENSION("dimension");

        private final String tag;

        Reason(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /** What the client observed this tick. */
    public record Observation(boolean inWorld, boolean playerDead, String dimension, boolean killedTarget) {
    }

    /** Seconds recorded after the moment that triggered a clip. */
    public static final double AFTERMATH_SECONDS = 4;
    /** Minimum gap between automatic clips. */
    public static final double COOLDOWN_SECONDS = 10;

    private boolean wasDead;
    private String lastDimension;
    private long pendingAtNanos = Long.MIN_VALUE;
    private long lastSaveNanos = Long.MIN_VALUE;
    private final Set<Reason> pendingReasons = new LinkedHashSet<>();

    /**
     * Feeds one tick of observations.
     *
     * @param enabled which reasons the player turned on
     * @return the reasons to save a clip for now, or an empty set
     */
    public Set<Reason> tick(long nowNanos, Observation seen, Set<Reason> enabled, boolean advancementEarned) {
        if (!seen.inWorld()) {
            wasDead = false;
            lastDimension = null;
            pendingReasons.clear();
            pendingAtNanos = Long.MIN_VALUE;
            return Set.of();
        }
        if (seen.playerDead() && !wasDead && enabled.contains(Reason.DEATH)) {
            trigger(Reason.DEATH, nowNanos);
        }
        wasDead = seen.playerDead();
        if (lastDimension != null && seen.dimension() != null && !seen.dimension().equals(lastDimension)
                && enabled.contains(Reason.DIMENSION)) {
            trigger(Reason.DIMENSION, nowNanos);
        }
        if (seen.dimension() != null) {
            lastDimension = seen.dimension();
        }
        if (seen.killedTarget() && enabled.contains(Reason.KILL)) {
            trigger(Reason.KILL, nowNanos);
        }
        if (advancementEarned && enabled.contains(Reason.ADVANCEMENT)) {
            trigger(Reason.ADVANCEMENT, nowNanos);
        }
        if (!pendingReasons.isEmpty() && nowNanos - pendingAtNanos >= (long) (AFTERMATH_SECONDS * 1e9)) {
            Set<Reason> due = Set.copyOf(new LinkedHashSet<>(pendingReasons));
            pendingReasons.clear();
            pendingAtNanos = Long.MIN_VALUE;
            lastSaveNanos = nowNanos;
            return due;
        }
        return Set.of();
    }

    private void trigger(Reason reason, long nowNanos) {
        if (pendingReasons.isEmpty()) {
            if (lastSaveNanos != Long.MIN_VALUE && nowNanos - lastSaveNanos < (long) (COOLDOWN_SECONDS * 1e9)) {
                return;
            }
            pendingAtNanos = nowNanos;
        }
        pendingReasons.add(reason);
    }

    /** File-name tag for a set of reasons, e.g. {@code "death"} or {@code "kill-advancement"}. */
    public static String tag(Set<Reason> reasons) {
        return String.join("-", reasons.stream().sorted().map(Reason::tag).toList());
    }
}
