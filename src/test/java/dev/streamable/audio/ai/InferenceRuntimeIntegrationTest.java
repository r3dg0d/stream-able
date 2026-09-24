package dev.streamable.audio.ai;

import dev.streamable.config.MicrophoneSettings;
import dev.streamable.runtime.HttpFetcher;
import dev.streamable.runtime.RuntimeContext;
import dev.streamable.runtime.RuntimeDownloader;
import dev.streamable.runtime.RuntimeInstaller;
import dev.streamable.runtime.RuntimeManager;
import dev.streamable.runtime.RuntimeManifest;
import dev.streamable.runtime.RuntimePlatform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production path end to end: pinned ONNX Runtime jar -> verified install
 * with native extraction -> isolated class loader -> manager -> real model.
 * The artifacts are served from a local directory (STREAMABLE_MODEL_DIR), so the
 * checksums pinned in the real manifest are exercised against the real bytes.
 */
class InferenceRuntimeIntegrationTest {

    @TempDir
    Path root;

    private String previousNativePath;

    @org.junit.jupiter.api.BeforeEach
    void rememberNativePath() {
        previousNativePath = System.getProperty("onnxruntime.native.path");
    }

    /**
     * The runtime points ONNX Runtime's JVM-wide native path at its install
     * directory, which is this test's temporary directory. Restore it so tests
     * that load ONNX Runtime from the test classpath afterwards are unaffected.
     */
    @org.junit.jupiter.api.AfterEach
    void restoreNativePath() {
        if (previousNativePath == null) {
            System.clearProperty("onnxruntime.native.path");
        } else {
            System.setProperty("onnxruntime.native.path", previousNativePath);
        }
    }

    @Test
    void realRuntimeLoadsInIsolationAndProcessesAudio() throws Exception {
        String env = System.getenv("STREAMABLE_MODEL_DIR");
        Assumptions.assumeTrue(env != null, "STREAMABLE_MODEL_DIR not set");
        Path dir = Path.of(env);
        Map<String, Path> files = Map.of(
                "onnxruntime-1.30.0.jar", dir.resolve("ort-1.30.0.jar"),
                "dpdfnet2_48khz_hr.onnx", dir.resolve("dpdfnet2_48khz_hr.onnx"));
        Assumptions.assumeTrue(files.values().stream().allMatch(Files::exists));

        HttpFetcher local = (uri, start) -> {
            String name = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
            Path file = files.get(name);
            if (file == null) {
                return new HttpFetcher.Response(404, 0, java.io.InputStream.nullInputStream());
            }
            byte[] data = Files.readAllBytes(file);
            return new HttpFetcher.Response(200, data.length, new ByteArrayInputStream(data));
        };
        RuntimeContext context = new RuntimeContext(root, RuntimePlatform.current(), RuntimeManifest.loadBundled(),
                new RuntimeDownloader(local), new RuntimeInstaller(), Executors.newFixedThreadPool(2));
        RuntimeManager runtimes = RuntimeManager.create(context);
        NoiseCancellationStage stage = new NoiseCancellationStage();
        NoiseCancellationManager manager = new NoiseCancellationManager(runtimes, stage);

        MicrophoneSettings settings = new MicrophoneSettings();
        settings.noise.level = MicrophoneSettings.NoiseLevel.BALANCED;
        settings.noise.backend = MicrophoneSettings.NoiseBackend.DPDFNET;
        settings.validate();
        manager.apply(settings);
        NoiseCancellationManager.Status status = null;
        for (int i = 0; i < 600; i++) {
            status = manager.status();
            if (status.state() != NoiseCancellationManager.Status.State.LOADING) {
                break;
            }
            Thread.sleep(50);
        }
        System.out.println("Manager status: " + status);
        assertEquals(NoiseCancellationManager.Status.State.ACTIVE, status.state(), String.valueOf(status));
        assertTrue(Files.isDirectory(root.resolve("onnxruntime/1.30.0/native")), "natives extracted at install");
        assertTrue(status.engineVersion().contains("1.30"), status.engineVersion());

        // The engine class must come from the isolated loader, not the test classpath.
        InferenceEngine engine = manager.onnxRuntime().engine();
        assertTrue(engine.getClass().getClassLoader() instanceof IsolatedModelLoader);

        stage.configure(settings);
        java.util.Random random = new java.util.Random(2);
        float[] block = new float[480];
        long start = System.nanoTime();
        for (int b = 0; b < 500; b++) {                 // 5 seconds of noisy "room"
            for (int i = 0; i < 480; i++) {
                block[i] = (float) ((random.nextDouble() * 2 - 1) * 0.05);
            }
            stage.process(block, 0, 480);
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("Stage over 5 s of audio: %.3f s CPU (RTF %.3f), inference %.3f ms/hop, latency %.1f ms, late %d%n",
                seconds, seconds / 5, stage.averageInferenceMillis(), stage.latencyMillis(), stage.lateSamples());
        assertTrue(stage.frames() >= 490);
        assertEquals(0, stage.lateSamples());
        manager.close();
        runtimes.close();
    }
}
