package dev.streamable.ffmpeg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class FFmpegProcessesTest {
    private static List<String> command(String mode, Path pid) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classes = Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return List.of(java, "-cp", classes, Child.class.getName(), mode, pid.toString());
    }

    @Test
    void drainsBothPipesWithoutUnboundedDiagnosticMemory(@TempDir Path dir) throws Exception {
        FFmpegProcesses.Result result = FFmpegProcesses.run(command("noisy", dir.resolve("pid")), 10);
        assertEquals(0, result.exitCode());
        assertEquals(65536, result.output().length());
        assertTrue(result.output().chars().allMatch(c -> c == 'x'));
    }

    @Test
    void preservesFailureStatusAndDiagnostics(@TempDir Path dir) throws Exception {
        FFmpegProcesses.Result result = FFmpegProcesses.run(command("error", dir.resolve("pid")), 10);
        assertEquals(7, result.exitCode());
        assertEquals("fixture encoder error", result.output());
    }

    @Test
    void interruptionTerminatesAndReapsTheOwnedProcess(@TempDir Path dir) throws Exception {
        Path pid = dir.resolve("pid");
        List<String> command = command("silent", pid);
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        Thread worker = Thread.ofPlatform().daemon(true).start(() -> {
            try {
                FFmpegProcesses.run(command, 120);
                result.complete(null);
            } catch (Throwable e) { result.complete(e); }
        });
        ProcessHandle child = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(pid) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pid));
            child = ProcessHandle.of(Long.parseLong(Files.readString(pid))).orElseThrow();
            worker.interrupt();
            assertInstanceOf(InterruptedException.class, result.get(3, TimeUnit.SECONDS));
            assertFalse(child.isAlive());
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
            worker.interrupt();
            worker.join(2000);
        }
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            Path pid = Path.of(args[1]);
            Path pending = pid.resolveSibling(pid.getFileName() + ".pending");
            Files.writeString(pending, Long.toString(ProcessHandle.current().pid()));
            Files.move(pending, pid); // Publish only after the PID is fully written.
            switch (args[0]) {
                case "silent" -> Thread.sleep(120000);
                case "noisy" -> {
                    byte[] bytes = new byte[8192];
                    java.util.Arrays.fill(bytes, (byte) 'x');
                    for (int i = 0; i < 128; i++) {
                        System.out.write(bytes);
                        System.err.write(bytes);
                    }
                }
                case "error" -> { System.err.print("fixture encoder error"); System.exit(7); }
                default -> throw new IllegalArgumentException("Unknown fixture");
            }
        }
    }
}
