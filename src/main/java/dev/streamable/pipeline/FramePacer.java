package dev.streamable.pipeline;

/**
 * Decides how many frames an output owes, so each encoder receives exactly its
 * own constant rate regardless of how fast the game renders.
 *
 * <h2>Why this is needed</h2>
 * <p>Frames arrive at whatever rate the game renders - 180 fps on a
 * high-refresh monitor, 45 in a busy world - but the encoder is fed raw video
 * declared at a fixed rate and derives every timestamp from the frame
 * <em>count</em>. So the count must equal {@code elapsed x fps} at all times:
 * a game rendering at 180 fps must not push 180 fps into a 60 fps stream, and a
 * slow frame must be covered by repeating the previous one.</p>
 *
 * <h2>Exact timeline</h2>
 * <p>Deadlines are computed as {@code start + n * 1e9 / fps} with integer
 * arithmetic on the frame index rather than by accumulating a rounded interval,
 * so 60 fps does not drift by the 0.67 ns per frame that a truncated
 * {@code 16_666_666} ns step would lose. Time is <em>never</em> discarded: after
 * a stall (world load, shader compile) the owed frames are reported, and the
 * pipeline emits them as repeats of the last picture. Dropping that time instead
 * would permanently shift video against the wall-clock-driven audio.</p>
 *
 * <p>Pure and clock-injected, so the behaviour is unit-testable.</p>
 */
public final class FramePacer {

    private final int fps;
    private long startNanos;
    private boolean started;
    private long emittedFrames;
    private long skippedRenders;
    private long repeatedFrames;
    private long largestCatchUp;

    public FramePacer(int fps) {
        this.fps = Math.clamp(fps, 1, 480);
    }

    public int fps() {
        return fps;
    }

    /**
     * Restarts the timeline. The clock is anchored on the first frame rather
     * than here, because a session starts when frames start arriving.
     */
    public void reset(long nowNanos) {
        started = false;
        startNanos = nowNanos;
        emittedFrames = 0;
        skippedRenders = 0;
        repeatedFrames = 0;
        largestCatchUp = 0;
    }

    /** Frames that should exist by {@code nowNanos}, counting the one at t=0. */
    private long framesOwedBy(long nowNanos) {
        long elapsed = Math.max(0, nowNanos - startNanos);
        // elapsed * fps overflows only after ~600 years at 480 fps.
        return elapsed * fps / 1_000_000_000L + 1;
    }

    /**
     * How many copies of the current picture the encoder is owed now.
     *
     * @return {@code 0} when the game is ahead of the output rate and this
     * render should not be captured, {@code 1} normally, more to cover a gap
     */
    public int framesDue(long nowNanos) {
        if (!started) {
            started = true;
            startNanos = nowNanos;
            emittedFrames = 1;
            return 1;
        }
        long owed = framesOwedBy(nowNanos) - emittedFrames;
        if (owed <= 0) {
            skippedRenders++;
            return 0;
        }
        int due = (int) Math.min(owed, Integer.MAX_VALUE);
        emittedFrames += due;
        if (due > 1) {
            repeatedFrames += due - 1;
            largestCatchUp = Math.max(largestCatchUp, due);
        }
        return due;
    }

    /** Earliest nanosecond offset (from the timeline start) at which frame {@code index} is due. */
    public long deadlineOffsetNanos(long index) {
        return (index * 1_000_000_000L + fps - 1) / fps;
    }

    public long emittedFrames() {
        return emittedFrames;
    }

    /** Renders not captured because the game outran the output rate. */
    public long skippedFrames() {
        return skippedRenders;
    }

    /** Frames repeated because the game rendered slower than the output rate. */
    public long duplicatedFrames() {
        return repeatedFrames;
    }

    /** The longest single catch-up, in frames; a hint that a stall happened. */
    public long largestCatchUp() {
        return largestCatchUp;
    }
}
