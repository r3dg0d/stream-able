/*
 * Replaces Record-able's Micrometer-based metrics with an equivalent
 * self-contained implementation. The public API is unchanged so the overlay and
 * diagnostics code carried over from Record-able keeps working; the dependency
 * on io.micrometer is dropped because a Minecraft mod should not ship a metrics
 * framework to count frames.
 */
package dev.streamable.util;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Capture/encode statistics for the recording overlay and stream health HUD.
 *
 * <p>Tracks frame rates, queue occupancy, drops and rolling average latencies.
 * All counters are lock-free: they are written from the render and encoder
 * threads and read by the UI, and none of them is worth a lock.</p>
 */
public final class PerformanceMetrics {

    private static final PerformanceMetrics INSTANCE = new PerformanceMetrics();

    private final AtomicLong framesCaptured = new AtomicLong();
    private final AtomicLong framesDropped = new AtomicLong();
    private final AtomicLong framesEncoded = new AtomicLong();
    private final AtomicLong adaptiveDrops = new AtomicLong();

    private final DoubleAdder captureLatencyNanos = new DoubleAdder();
    private final DoubleAdder encodeLatencyNanos = new DoubleAdder();
    private final AtomicLong captureLatencySamples = new AtomicLong();
    private final AtomicLong encodeLatencySamples = new AtomicLong();

    private final AtomicLong queueSize = new AtomicLong();
    private final AtomicLong queueCapacity = new AtomicLong(240);
    private final AtomicLong currentFps = new AtomicLong();
    private final AtomicLong encoderFps = new AtomicLong();
    private final AtomicLong memoryUsedMiB = new AtomicLong();
    private final AtomicLong fileSizeBytes = new AtomicLong();

    private PerformanceMetrics() {
    }

    public static PerformanceMetrics getInstance() {
        return INSTANCE;
    }

    public void recordFrameCapture(long durationNanos) {
        framesCaptured.incrementAndGet();
        if (durationNanos > 0) {
            captureLatencyNanos.add(durationNanos);
            captureLatencySamples.incrementAndGet();
        }
    }

    public void recordFrameEncode(long durationNanos) {
        framesEncoded.incrementAndGet();
        if (durationNanos > 0) {
            encodeLatencyNanos.add(durationNanos);
            encodeLatencySamples.incrementAndGet();
        }
    }

    public void recordFrameDrop() {
        framesDropped.incrementAndGet();
    }

    public void recordAdaptiveDrop() {
        adaptiveDrops.incrementAndGet();
    }

    public void updateQueueStats(int size, int capacity) {
        queueSize.set(Math.max(0, size));
        queueCapacity.set(Math.max(1, capacity));
    }

    public void updateFps(long captureFps, long encodeFps) {
        currentFps.set(Math.max(0, captureFps));
        encoderFps.set(Math.max(0, encodeFps));
    }

    public void updateMemory(long usedMiB) {
        memoryUsedMiB.set(Math.max(0, usedMiB));
    }

    public void updateFileSize(long bytes) {
        fileSizeBytes.set(Math.max(0, bytes));
    }

    /** Clears every counter, called when a new session starts. */
    public void reset() {
        framesCaptured.set(0);
        framesDropped.set(0);
        framesEncoded.set(0);
        adaptiveDrops.set(0);
        captureLatencyNanos.reset();
        encodeLatencyNanos.reset();
        captureLatencySamples.set(0);
        encodeLatencySamples.set(0);
        queueSize.set(0);
        currentFps.set(0);
        encoderFps.set(0);
        memoryUsedMiB.set(0);
        fileSizeBytes.set(0);
    }

    private static double averageMillis(DoubleAdder totalNanos, AtomicLong samples) {
        long count = samples.get();
        return count == 0 ? 0.0 : totalNanos.sum() / count / 1_000_000.0;
    }

    public double getAvgCaptureLatencyMs() {
        return averageMillis(captureLatencyNanos, captureLatencySamples);
    }

    public double getAvgEncodeLatencyMs() {
        return averageMillis(encodeLatencyNanos, encodeLatencySamples);
    }

    /** Remaining headroom in the frame queue, as a percentage. */
    public double getBufferHealthPercent() {
        long capacity = queueCapacity.get();
        return capacity <= 0 ? 100.0 : Math.max(0.0, 100.0 - (queueSize.get() * 100.0 / capacity));
    }

    public long getTotalCaptured() {
        return framesCaptured.get();
    }

    public long getTotalDropped() {
        return framesDropped.get();
    }

    public long getTotalEncoded() {
        return framesEncoded.get();
    }

    public long getAdaptiveDrops() {
        return adaptiveDrops.get();
    }

    public long getCaptureFps() {
        return currentFps.get();
    }

    public long getEncoderFps() {
        return encoderFps.get();
    }

    public long getMemoryUsedMiB() {
        return memoryUsedMiB.get();
    }

    public long getQueueSize() {
        return queueSize.get();
    }

    public long getQueueCapacity() {
        return queueCapacity.get();
    }

    public long getFileSizeBytes() {
        return fileSizeBytes.get();
    }

    /** One-line summary for the performance overlay. */
    public String getCompactSummary() {
        return String.format(java.util.Locale.ROOT,
                "%d fps cap / %d fps enc | queue %d/%d | dropped %d | %.1f ms enc",
                getCaptureFps(), getEncoderFps(), getQueueSize(), getQueueCapacity(),
                getTotalDropped(), getAvgEncodeLatencyMs());
    }
}
