package dev.streamable.ui;

import dev.streamable.audio.mic.MicrophoneProcessor;

/**
 * Compact Stream HUD badge for microphone DSP pressure.
 *
 * <p>Uses the same thresholds as Stream Health: backlog above
 * {@value #BACKLOG_WARN_BLOCKS} blocks or a live {@code overloaded} flag
 * surfaces a warning pill; any dropped blocks escalate to a critical pill.
 * Pure inputs so the badge can be unit-tested without the Minecraft client.</p>
 */
public final class MicHudBadge {

    /** Matches Stream Health's backlog warning threshold. */
    public static final int BACKLOG_WARN_BLOCKS = 3;

    public enum Level {
        NONE,
        WARNING,
        CRITICAL
    }

    /**
     * @param label short pill text ({@code MIC DSP} / {@code MIC DROP}), empty when absent
     * @param level severity for colouring the pill / meter caption
     */
    public record Badge(String label, Level level) {
        public static final Badge ABSENT = new Badge("", Level.NONE);

        public boolean present() {
            return level != Level.NONE;
        }
    }

    private MicHudBadge() {
    }

    /**
     * Resolves the compact HUD pill for the current mic state.
     *
     * @param captureEnabled Studio → Audio "Use my microphone" / recording.captureMicrophone
     * @param capturing      the microphone service is actually capturing
     * @param stats          live DSP stats; ignored when not capturing (may be null)
     */
    public static Badge of(boolean captureEnabled, boolean capturing, MicrophoneProcessor.Stats stats) {
        if (!captureEnabled || !capturing || stats == null) {
            return Badge.ABSENT;
        }
        if (stats.droppedBlocks() > 0) {
            return new Badge("MIC DROP", Level.CRITICAL);
        }
        if (stats.overloaded() || stats.backlogBlocks() > BACKLOG_WARN_BLOCKS) {
            return new Badge("MIC DSP", Level.WARNING);
        }
        return Badge.ABSENT;
    }

    /**
     * Caption for the detailed HUD mic meter row: mute wins, then DSP pressure,
     * otherwise plain {@code MIC}.
     */
    public static String meterCaption(boolean muted, Badge badge) {
        if (muted) {
            return "MIC MUTED";
        }
        if (badge.level() == Level.CRITICAL) {
            return "MIC DROP";
        }
        if (badge.level() == Level.WARNING) {
            return "MIC DSP";
        }
        return "MIC";
    }
}
