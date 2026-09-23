package dev.streamable.pipeline;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded pool of frame buffers for one output size.
 *
 * <p>Allocation is capped: once {@code maxBuffers} frames are in flight,
 * {@link #acquire} returns {@code null} and the caller drops the frame. That
 * turns "the encoder cannot keep up" into a counted dropped frame instead of
 * unbounded memory growth, and it keeps the render thread allocation-free in
 * steady state.</p>
 */
public final class FrameBufferPool {

    private final int frameBytes;
    private final int maxBuffers;
    private final ArrayBlockingQueue<PooledFrame> free;
    private final AtomicInteger allocated = new AtomicInteger();
    private final AtomicLong exhaustedCount = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong();

    public FrameBufferPool(int frameBytes, int maxBuffers) {
        if (frameBytes <= 0) {
            throw new IllegalArgumentException("frameBytes must be positive");
        }
        this.frameBytes = frameBytes;
        this.maxBuffers = Math.max(2, maxBuffers);
        this.free = new ArrayBlockingQueue<>(this.maxBuffers);
    }

    public int frameBytes() {
        return frameBytes;
    }

    public int maxBuffers() {
        return maxBuffers;
    }

    public int allocatedBuffers() {
        return allocated.get();
    }

    /** Frames that could not be captured because every buffer was in flight. */
    public long exhaustedCount() {
        return exhaustedCount.get();
    }

    /** Bytes of heap held by this pool. */
    public long retainedBytes() {
        return (long) allocated.get() * frameBytes;
    }

    /**
     * A buffer with one reference held by the caller, or {@code null} when the
     * pool is exhausted.
     */
    public PooledFrame acquire(long captureNanos) {
        PooledFrame frame = free.poll();
        if (frame == null) {
            int count = allocated.get();
            while (count < maxBuffers) {
                if (allocated.compareAndSet(count, count + 1)) {
                    frame = new PooledFrame(this, frameBytes);
                    break;
                }
                count = allocated.get();
            }
            if (frame == null) {
                exhaustedCount.incrementAndGet();
                return null;
            }
        }
        frame.reset(frameBytes, captureNanos, sequence.incrementAndGet());
        return frame;
    }

    void recycle(PooledFrame frame) {
        if (!free.offer(frame)) {
            allocated.decrementAndGet();
        }
    }
}
