package dev.streamable.ffmpeg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class FFmpegManagerTimeoutTest {
    @Test
    void silentConfiguredBinaryCannotHangResolution(@TempDir Path dir) throws Exception {
        Path shell = Files.isExecutable(Path.of("/bin/sh")) ? Path.of("/bin/sh")
                : Path.of("/run/current-system/sw/bin/sh");
        Assumptions.assumeTrue(Files.isExecutable(shell));
        Path pidFile = dir.resolve("child.pid");
        Path executable = dir.resolve("silent-ffmpeg");
        // The shell is replaced, so the owned PID is also the sleeping child.
        Files.writeString(executable, "#!" + shell + "\necho $$ > '" + pidFile + "'\nexec sleep 120\n");
        assertTrue(executable.toFile().setExecutable(true));
        FFmpegManager manager = new FFmpegManager();
        manager.setAllowSystemPath(false);
        manager.setConfiguredPath(executable.toString());
        CompletableFuture<FFmpegManager.Resolution> result = new CompletableFuture<>();
        Thread worker = Thread.ofPlatform().daemon(true).start(() -> {
            try { result.complete(manager.resolution()); }
            catch (Throwable e) { result.completeExceptionally(e); }
        });
        ProcessHandle child = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile), "the fake binary must actually start");
            child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow();
            assertFalse(result.get(18, TimeUnit.SECONDS).isAvailable(),
                    "the 15-second probe deadline must cover waiting for output");
            assertFalse(child.isAlive(), "timed-out encoder must be reaped");
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
            worker.interrupt();
            worker.join(2000);
        }
    }
}
