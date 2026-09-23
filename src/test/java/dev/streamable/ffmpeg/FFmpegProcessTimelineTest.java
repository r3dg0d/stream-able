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
 * Exercises the real writer thread against a real process. Uses a shell
 * {@code cat} to a file as a stand-in consumer, so it needs no FFmpeg.
 */
class FFmpegProcessTimelineTest {

    @Test
    void droppedPicturesStillCountTowardsTheTimeline() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")) || Files.isExecutable(Path.of("/run/current-system/sw/bin/sh")));
        Path out = Files.createTempFile("timeline", ".raw");
        String sh = Files.isExecutable(Path.of("/bin/sh")) ? "/bin/sh" : "/run/current-system/sw/bin/sh";
        // Slow consumer: sleeps first so the 2-slot queue overflows.
        FFmpegProcess process = new FFmpegProcess(List.of(sh, "-c", "sleep 0.5; cat > '" + out + "'"), 2);
        process.start();
        FrameBufferPool pool = new FrameBufferPool(16, 64);
        int frames = 40;
        for (int i = 0; i < frames; i++) {
            PooledFrame frame = pool.acquire(i);
            java.util.Arrays.fill(frame.data(), (byte) i);
            process.offerFrame(frame, 1);
            frame.release();
        }
        assertTrue(process.framesDropped() > 0, "the tiny queue must overflow");
        // Stopping flushes any owed frames as repeats of the last picture.
        process.stop();
        assertFalse(process.isAlive(), "child process must not be orphaned");
        long written = Files.size(out) / 16;
        assertEquals(frames, written, "every frame period is represented in the output");
        assertEquals(frames, process.framesWritten());
        Files.deleteIfExists(out);
    }
}
