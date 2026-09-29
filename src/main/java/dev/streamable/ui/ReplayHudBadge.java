package dev.streamable.ui;

import java.util.Locale;

/**
 * Compact Stream HUD badge for the replay buffer fill level.
 *
 * <p>Shows how full the rolling buffer is as {@code REPLAY N%}, switches to
 * {@code SAVING} while a clip is being written, and stays absent when the
 * buffer is off. Pure inputs so the badge can be unit-tested without the
 * Minecraft client or a live FFmpeg session.</p>
 */
public final class ReplayHudBadge {

    public enum Level {
        NONE,
        /** Buffer running and still filling toward the configured length. */
        FILLING,
        /** Buffer has (at least) the configured seconds ready to save. */
        FULL,
        /** A clip encode/mux is in progress. */
        SAVING
    }

    /**
     * @param label short pill text ({@code REPLAY 42%} / {@code REPLAY 100%} /
     *              {@code SAVING}), empty when absent
     * @param level severity / colour hint for the pill
     * @param fillPercent 0–100 of configured length currently buffered (0 when absent)
     */
    public record Badge(String label, Level level, int fillPercent) {
        public static final Badge ABSENT = new Badge("", Level.NONE, 0);

        public boolean present() {
            return level != Level.NONE;
        }
    }

    private ReplayHudBadge() {
    }

    /**
     * How full the buffer is, clamped to 0–100.
     *
     * @param bufferedSeconds  seconds currently available ({@link
     *                         dev.streamable.recording.replay.ReplayBuffer#bufferedSeconds()})
     * @param configuredSeconds configured clip length (must be &gt; 0)
     */
    public static int fillPercent(double bufferedSeconds, int configuredSeconds) {
        if (configuredSeconds <= 0 || !Double.isFinite(bufferedSeconds) || bufferedSeconds <= 0) {
            return 0;
        }
        return (int) Math.clamp(Math.round(100.0 * bufferedSeconds / configuredSeconds), 0, 100);
    }

    /**
     * Resolves the compact HUD pill for the current replay-buffer state.
     *
     * @param running            buffer encoder is running
     * @param saving             a clip save is in flight
     * @param bufferedSeconds    seconds currently available to save
     * @param configuredSeconds  configured buffer length in seconds
     */
    public static Badge of(boolean running, boolean saving, double bufferedSeconds, int configuredSeconds) {
        if (!running) {
            return Badge.ABSENT;
        }
        int percent = fillPercent(bufferedSeconds, configuredSeconds);
        if (saving) {
            return new Badge("SAVING", Level.SAVING, percent);
        }
        if (percent >= 100) {
            return new Badge("REPLAY 100%", Level.FULL, 100);
        }
        return new Badge("REPLAY " + percent + "%", Level.FILLING, percent);
    }

    /**
     * Detailed HUD line under the replay pill: buffered vs configured seconds.
     *
     * @param bufferedSeconds   seconds currently available to save
     * @param configuredSeconds configured buffer length
     * @param saving            a clip encode/mux is in flight
     */
    public static String detailLine(double bufferedSeconds, int configuredSeconds, boolean saving) {
        if (configuredSeconds <= 0 || !Double.isFinite(bufferedSeconds)) {
            return saving ? "Replay · saving" : "Replay —";
        }
        double shown = Math.clamp(bufferedSeconds, 0.0, configuredSeconds);
        String base = String.format(Locale.ROOT, "Replay %.0f / %d s", shown, configuredSeconds);
        return saving ? base + " · saving" : base;
    }
}
