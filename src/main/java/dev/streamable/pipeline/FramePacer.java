package dev.streamable.pipeline;

/**
 * Decides how many frames to emit, so the encoder receives a constant rate.
 *
 * <h2>Why this is needed</h2>
 * <p>Frames arrive at whatever rate the game renders - 300 fps on a menu, 45 in
 * a busy world - but the encoder is fed raw video declared at a fixed rate and
 * derives every timestamp from the frame <em>count</em>. Handing it one frame
 * per render therefore breaks the timeline in both directions: render faster
 * than the target and the stream plays fast while the queue floods; render
 * slower and it plays in slow motion.</p>
 *
 * <p>This converts render events into a steady output rate - skipping when the
 * game is ahead, repeating the last frame when it falls behind - so the frame
 * count always matches elapsed time.</p>
 *
 * <p>Pure and clock-injected, so the behaviour is unit-testable.</p>
 */
public final class FramePacer {

    /**
     * Most duplicates emitted for a single render.
     *
     * <p>A long stall (world load, shader compile) would otherwise produce a
     * burst of hundreds of duplicate frames that floods the queue at exactly the
     * moment the machine is least able to cope.</p>
     */
    private static final int MAX_CATCH_UP_FRAMES = 3;

    /** Beyond this much drift, resynchronise instead of trying to catch up. */
    private static final long RESYNC_THRESHOLD_NANOS = 1_000_000_000L;

    private final long frameIntervalNanos;
    private long nextFrameDueNanos;
    private boolean started;
    private long emittedFrames;
    private long skippedFrames;
    private long duplicatedFrames;

    public FramePacer(int fps) {
        this.frameIntervalNanos = 1_000_000_000L / Math.clamp(fps, 1, 480);
    }

    /**
     * Restarts the timeline.
     *
     * <p>The clock is anchored on the <em>first frame</em> rather than here,
     * because a session starts when frames start arriving. Anchoring at reset
     * would leave the pacer owing a frame for the interval before the first one
     * showed up, and every session would open with a duplicate.</p>
     */
    public void reset(long nowNanos) {
        started = false;
        nextFrameDueNanos = nowNanos;
        emittedFrames = 0;
        skippedFrames = 0;
        duplicatedFrames = 0;
    }

    /**
     * How many copies of the current frame the encoder is owed.
     *
     * @return {@code 0} when the game is ahead of the target rate and this
     * render should be skipped, {@code 1} normally, or more to fill a gap
     */
    public int framesDue(long nowNanos) {
        if (!started) {
            // Anchor the timeline on this frame and emit it.
            started = true;
            nextFrameDueNanos = nowNanos + frameIntervalNanos;
            emittedFrames++;
            return 1;
        }
        if (nowNanos < nextFrameDueNanos) {
            skippedFrames++;
            return 0;
        }
        if (nowNanos - nextFrameDueNanos > RESYNC_THRESHOLD_NANOS) {
            // A long stall: pick the timeline up from here rather than emitting
            // a flood of duplicates for time that has already passed.
            nextFrameDueNanos = nowNanos + frameIntervalNanos;
            emittedFrames++;
            return 1;
        }
        // Computed rather than looped: with `>=` inside a loop, a render that
        // lands exactly on the deadline satisfies the condition twice and emits
        // a spurious duplicate on every perfectly-paced frame.
        long overshoot = nowNanos - nextFrameDueNanos;
        int due = (int) Math.min(MAX_CATCH_UP_FRAMES, overshoot / frameIntervalNanos + 1);
        nextFrameDueNanos += (long) due * frameIntervalNanos;
        emittedFrames += due;
        if (due > 1) {
            duplicatedFrames += due - 1;
        }
        return due;
    }

    public long emittedFrames() {
        return emittedFrames;
    }

    /** Renders skipped because the game outran the target rate. */
    public long skippedFrames() {
        return skippedFrames;
    }

    /** Frames repeated to cover a shortfall. */
    public long duplicatedFrames() {
        return duplicatedFrames;
    }
}
