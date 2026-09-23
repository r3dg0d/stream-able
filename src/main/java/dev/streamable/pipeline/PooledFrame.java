package dev.streamable.pipeline;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A reference-counted raw video frame borrowed from a {@link FrameBufferPool}.
 *
 * <p>One captured frame is often consumed several times - by the recorder and
 * by each encoder group, and repeated when the game falls behind the output
 * frame rate - so it is shared rather than copied. Every holder calls
 * {@link #retain()} before keeping it and {@link #release()} when done; the
 * buffer returns to its pool when the count reaches zero.</p>
 */
public final class PooledFrame {

    private final FrameBufferPool pool;
    private final byte[] data;
    private final AtomicInteger references = new AtomicInteger();
    private volatile int length;
    private volatile long captureNanos;
    private volatile long sequence;

    PooledFrame(FrameBufferPool pool, int capacity) {
        this.pool = pool;
        this.data = new byte[capacity];
    }

    /** Standalone frame wrapping existing bytes (tests, one-off frames). */
    public static PooledFrame wrap(byte[] bytes) {
        PooledFrame frame = new PooledFrame(null, 0, bytes);
        frame.references.set(1);
        return frame;
    }

    private PooledFrame(FrameBufferPool pool, int ignored, byte[] bytes) {
        this.pool = pool;
        this.data = bytes;
        this.length = bytes.length;
    }

    void reset(int length, long captureNanos, long sequence) {
        this.length = length;
        this.captureNanos = captureNanos;
        this.sequence = sequence;
        references.set(1);
    }

    public byte[] data() {
        return data;
    }

    public int length() {
        return length;
    }

    public void setLength(int length) {
        this.length = Math.clamp(length, 0, data.length);
    }

    /** {@link System#nanoTime()} when the frame was read back from the GPU. */
    public long captureNanos() {
        return captureNanos;
    }

    public long sequence() {
        return sequence;
    }

    public int references() {
        return references.get();
    }

    public PooledFrame retain() {
        int previous = references.getAndIncrement();
        if (previous <= 0) {
            references.decrementAndGet();
            throw new IllegalStateException("Frame retained after it was released");
        }
        return this;
    }

    public void release() {
        int remaining = references.decrementAndGet();
        if (remaining == 0 && pool != null) {
            pool.recycle(this);
        } else if (remaining < 0) {
            references.set(0);
            throw new IllegalStateException("Frame released more times than retained");
        }
    }
}
