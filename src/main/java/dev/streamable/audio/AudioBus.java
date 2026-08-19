package dev.streamable.audio;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One input of the program mix.
 *
 * <p>Buses mirror what the player thinks of as separate sound sources - the
 * game, their microphone, proximity voice chat, browser overlays - each with
 * its own level and mute. The stream receives their sum; local recordings can
 * still keep them on separate tracks.</p>
 */
public final class AudioBus {

    /** The fixed set of buses. Adding one means adding a capture source for it. */
    public enum Kind {
        GAME("Game"),
        MICROPHONE("Microphone"),
        VOICE_CHAT("Plasmo Voice"),
        BROWSER("Browser Sources");

        private final String displayName;

        Kind(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private final Kind kind;
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicBoolean muted = new AtomicBoolean(false);
    private volatile float volume = 1.0f;
    private final AtomicLong samplesReceived = new AtomicLong();
    /** Rolling peak in {@code [0,1]} for the level meter. */
    private volatile float peakLevel;

    public AudioBus(Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean enabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    public boolean muted() {
        return muted.get();
    }

    public void setMuted(boolean value) {
        muted.set(value);
    }

    public float volume() {
        return volume;
    }

    public void setVolume(float value) {
        this.volume = (float) Math.clamp(value, 0.0, 2.0);
    }

    /** The factor actually applied when mixing. */
    public float effectiveGain() {
        return enabled.get() && !muted.get() ? volume : 0.0f;
    }

    public long samplesReceived() {
        return samplesReceived.get();
    }

    public float peakLevel() {
        return peakLevel;
    }

    void noteSamples(long count, float peak) {
        samplesReceived.addAndGet(count);
        // Decay slowly so the meter is readable rather than flickering.
        peakLevel = Math.max(peak, peakLevel * 0.85f);
    }

    void reset() {
        samplesReceived.set(0);
        peakLevel = 0;
    }
}
