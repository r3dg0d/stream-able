package dev.streamable.ffmpeg;

import dev.streamable.pipeline.FrameBufferPool;
import dev.streamable.pipeline.PooledFrame;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The encoder writer keeps the output timeline intact when pictures are dropped:
 * each lost picture is later written as a repeat of the next one so FFmpeg's
 * frame-count timestamps never drift against audio. The child process is just
 * {@code cat} to a file as a stand-in consumer, so it needs no FFmpeg.
 */
class FFmpegProcessTimelineTest {

    @Test
    void droppedPicturesStillCountTowardsTheTimeline() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")) || Files.isExecutable(Path.of("/run/current-system/sw/bin/sh")));
        Path out = Files.createTempFile("timeline", ".raw");
        String sh = Files.isExecutable(Path.of("/bin/sh")) ? "/bin/sh" : "/run/current-system/sw/bin/sh";
        // Large frames fill the OS pipe while the consumer sleeps, so the 2-slot
        // Java queue overflows. Tiny frames used to fit entirely in the pipe and
        // the drop assertion flaked on fast runners (GHA ubuntu).
        int frameBytes = 64 * 1024;
        FFmpegProcess process = new FFmpegProcess(List.of(sh, "-c", "sleep 0.8; cat > '" + out + "'"), 2);
        process.start();
        FrameBufferPool pool = new FrameBufferPool(frameBytes, 64);
        int frames = 40;
        for (int i = 0; i < frames; i++) {
            PooledFrame frame = pool.acquire(i);
            assertTrue(frame != null, "pool exhausted early");
            java.util.Arrays.fill(frame.data(), (byte) i);
            process.offerFrame(frame, 1);
            frame.release();
        }
        assertTrue(process.framesDropped() > 0, "the tiny queue must overflow");
        // Stopping flushes any owed frames as repeats of the last picture.
        process.stop();
        assertFalse(process.isAlive(), "child process must not be orphaned");
        long written = Files.size(out) / frameBytes;
        assertEquals(frames, written, "every frame period is represented in the output");
        assertEquals(frames, process.framesWritten());
        Files.deleteIfExists(out);
    }
}
