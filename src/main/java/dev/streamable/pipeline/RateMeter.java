package dev.streamable.pipeline;

/**
 * Events-per-second over a sliding one-second window, allocation free.
 * Used for render FPS, capture FPS and similar diagnostics.
 */
public final class RateMeter {

    private static final int CAPACITY = 1024;
    private final long[] times = new long[CAPACITY];
    private int head;
    private int size;

    public synchronized void tick(long nowNanos) {
        times[head] = nowNanos;
        head = (head + 1) % CAPACITY;
        size = Math.min(size + 1, CAPACITY);
    }

    /** Events in the last second, measured against the latest event. */
    public synchronized double perSecond() {
        if (size < 2) {
            return 0;
        }
        long newest = times[(head - 1 + CAPACITY) % CAPACITY];
        if (System.nanoTime() - newest > 1_500_000_000L) {
            return 0;
        }
        int count = 0;
        long oldest = newest;
        for (int i = 1; i <= size; i++) {
            long t = times[(head - i + CAPACITY) % CAPACITY];
            if (newest - t > 1_000_000_000L) {
                break;
            }
            count++;
            oldest = t;
        }
        if (count < 2 || newest == oldest) {
            return count;
        }
        return (count - 1) * 1e9 / (newest - oldest);
    }
}
