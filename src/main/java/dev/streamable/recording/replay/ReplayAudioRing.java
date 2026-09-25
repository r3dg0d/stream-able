package dev.streamable.recording.replay;

import dev.streamable.audio.AudioMixer;

/**
 * The last few minutes of the program mix, in memory, with the wall-clock
 * time of every sample.
 *
 * <p>The mixer emits a gap-free stream (exactly the samples elapsed time calls
 * for), so one anchor - the time of the first sample ever appended - fixes the
 * time of every later sample. A clip's audio is then cut by time, sample
 * exact, to line up with the video segments.</p>
 *
 * <p>Memory: 48 kHz stereo 16-bit is 192 kB per second, so a 5-minute buffer
 * holds about 58 MB.</p>
 */
public final class ReplayAudioRing {

    private static final int FRAME_BYTES = AudioMixer.CHANNELS * AudioMixer.BYTES_PER_SAMPLE;
    private static final int RATE = AudioMixer.SAMPLE_RATE;

    private final byte[] data;
    private final long capacityFrames;
    /** Frames ever appended; the ring holds the last {@code min(total, capacity)}. */
    private long totalFrames;
    /** Wall-clock time of frame 0; set by the first append. */
    private long anchorNanos = Long.MIN_VALUE;

    public ReplayAudioRing(double seconds) {
        this.capacityFrames = Math.max(RATE, (long) Math.ceil(seconds * RATE));
        this.data = new byte[Math.toIntExact(capacityFrames * FRAME_BYTES)];
    }

    /**
     * Appends one mixer block. {@code arrivalNanos} is when the block arrived;
     * the block covers the time just before it, so its first sample is one
     * block-duration earlier.
     */
    public synchronized void append(byte[] block, long arrivalNanos) {
        int frames = block.length / FRAME_BYTES;
        if (frames == 0) {
            return;
        }
        if (anchorNanos == Long.MIN_VALUE) {
            anchorNanos = arrivalNanos - frames * 1_000_000_000L / RATE;
        }
        for (int f = 0; f < frames; f++) {
            long slot = (totalFrames + f) % capacityFrames;
            System.arraycopy(block, f * FRAME_BYTES, data, (int) (slot * FRAME_BYTES), FRAME_BYTES);
        }
        totalFrames += frames;
    }

    /** Wall-clock time of the oldest sample still held, or {@link Long#MIN_VALUE} when empty. */
    public synchronized long oldestNanos() {
        if (anchorNanos == Long.MIN_VALUE) {
            return Long.MIN_VALUE;
        }
        long oldest = Math.max(0, totalFrames - capacityFrames);
        return anchorNanos + oldest * 1_000_000_000L / RATE;
    }

    /**
     * The audio from {@code fromNanos} lasting {@code frames} frames, as 48 kHz
     * stereo 16-bit PCM. Any part not held (before the oldest sample, or after
     * the newest) is silence, so the result always has the requested length.
     */
    public synchronized byte[] extract(long fromNanos, long frames) {
        byte[] out = new byte[Math.toIntExact(frames * FRAME_BYTES)];
        if (anchorNanos == Long.MIN_VALUE) {
            return out;
        }
        long startFrame = Math.floorDiv((fromNanos - anchorNanos) * RATE, 1_000_000_000L);
        long oldest = Math.max(0, totalFrames - capacityFrames);
        for (long i = 0; i < frames; i++) {
            long frame = startFrame + i;
            if (frame < oldest || frame >= totalFrames) {
                continue;
            }
            long slot = frame % capacityFrames;
            System.arraycopy(data, (int) (slot * FRAME_BYTES), out, (int) (i * FRAME_BYTES), FRAME_BYTES);
        }
        return out;
    }

    public synchronized double heldSeconds() {
        return Math.min(totalFrames, capacityFrames) / (double) RATE;
    }
}
