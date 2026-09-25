package dev.streamable.runtime;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONNX Runtime walks {@code /proc/self/cmdline} with a recursive regex while
 * creating its environment. With a launcher-sized command line that overflowed
 * the default 1 MB thread stack and crashed the whole JVM, so this runs in a
 * child JVM whose command line is padded well past what a large modpack uses.
 */
class NativeStackTest {

    @Test
    void stackGrowsWithTheCommandLine() {
        assertTrue(RuntimeManager.nativeStackBytes() >= 64L << 20);
    }

    @Test
    void onnxRuntimeStartsOnRuntimeThreadsWithALongCommandLine() throws Exception {
        Assumptions.assumeTrue(Files.isReadable(Path.of("/proc/self/cmdline")), "Linux only");
        String java = ProcessHandle.current().info().command().orElseThrow();
        Process child = new ProcessBuilder(java,
                "-Dstreamable.padding=" + "x".repeat(64_000),
                "-cp", System.getProperty("java.class.path"),
                Child.class.getName())
                .redirectErrorStream(true)
                .start();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(child.waitFor(2, TimeUnit.MINUTES), output);
        assertEquals(0, child.exitValue(), output);
        assertTrue(output.contains("ONNX Runtime ready"), output);
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            ExecutorService executor = Executors.newSingleThreadExecutor(
                    RuntimeManager.namedDaemonThreads("native-stack-test", RuntimeManager.nativeStackBytes()));
            String version = executor.submit(() -> ai.onnxruntime.OrtEnvironment.getEnvironment().getVersion())
                    .get(1, TimeUnit.MINUTES);
            System.out.println("ONNX Runtime ready " + version);
            executor.shutdown();
        }
    }
}
