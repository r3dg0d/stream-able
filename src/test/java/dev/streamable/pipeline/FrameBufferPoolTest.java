package dev.streamable.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameBufferPoolTest {

    @Test
    void buffersAreReusedOnceEveryHolderReleases() {
        FrameBufferPool pool = new FrameBufferPool(1024, 2);
        PooledFrame frame = pool.acquire(1);
        frame.retain();                   // e.g. queued for the recorder
        frame.release();                  // capture code done
        assertEquals(1, frame.references());
        frame.release();                  // recorder done
        PooledFrame again = pool.acquire(2);
        assertSame(frame, again, "released buffer is recycled, not reallocated");
        assertEquals(1, pool.allocatedBuffers());
    }

    @Test
    void poolIsBoundedAndReportsExhaustion() {
        FrameBufferPool pool = new FrameBufferPool(1024, 2);
        assertNotNull(pool.acquire(0));
        assertNotNull(pool.acquire(0));
        assertNull(pool.acquire(0), "a stalled encoder must not grow memory without bound");
        assertEquals(1, pool.exhaustedCount());
        assertEquals(2048, pool.retainedBytes());
    }

    @Test
    void doubleReleaseIsDetected() {
        FrameBufferPool pool = new FrameBufferPool(16, 2);
        PooledFrame frame = pool.acquire(0);
        frame.release();
        assertThrows(IllegalStateException.class, frame::retain);
    }

    @Test
    void worstCaseFrameSizeFitsWithoutOverflow() {
        dev.streamable.video.Resolution superUltrawide = new dev.streamable.video.Resolution(5120, 1440);
        assertEquals(22_118_400, superUltrawide.frameBytesInt(3));
        dev.streamable.video.Resolution max = new dev.streamable.video.Resolution(8192, 8192);
        assertEquals(268_435_456L, max.frameBytes(4));
    }
}
