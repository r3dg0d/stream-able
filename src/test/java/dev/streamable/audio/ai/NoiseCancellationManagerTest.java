package dev.streamable.audio.ai;

import dev.streamable.config.MicrophoneSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoiseCancellationManagerTest {

    private static NoiseCancellationStage.Backend backend(MicrophoneSettings.NoiseBackend choice) {
        ModelSpec spec = ModelSpec.forBackend(choice);
        return new NoiseCancellationStage.Backend(spec,
                new NoiseCancellationStageTest.IdentityModel(spec.fftSize() / 2 + 1), "mock");
    }

    private static MicrophoneSettings settings(MicrophoneSettings.NoiseBackend preference) {
        MicrophoneSettings s = new MicrophoneSettings();
        s.noise.level = MicrophoneSettings.NoiseLevel.BALANCED;
        s.noise.backend = preference;
        s.validate();
        return s;
    }

    private static NoiseCancellationManager.Status await(NoiseCancellationManager manager) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            NoiseCancellationManager.Status status = manager.status();
            if (status.state() != NoiseCancellationManager.Status.State.LOADING) {
                return status;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("manager did not settle");
    }

    @Test
    void fallbackOrderGoesFromHeavyToLight() {
        assertEquals(List.of(MicrophoneSettings.NoiseBackend.DPDFNET, MicrophoneSettings.NoiseBackend.DEEPFILTERNET,
                MicrophoneSettings.NoiseBackend.GTCRN), NoiseCancellationManager.fallbackOrder(MicrophoneSettings.NoiseBackend.AUTO));
        assertEquals(List.of(MicrophoneSettings.NoiseBackend.GTCRN),
                NoiseCancellationManager.fallbackOrder(MicrophoneSettings.NoiseBackend.GTCRN));
    }

    @Test
    void preferredBackendFailingFallsThroughToTheNext() throws Exception {
        List<MicrophoneSettings.NoiseBackend> tried = new ArrayList<>();
        NoiseCancellationStage stage = new NoiseCancellationStage();
        NoiseCancellationManager manager = new NoiseCancellationManager(stage, (choice, progress) -> {
            tried.add(choice);
            if (choice == MicrophoneSettings.NoiseBackend.DPDFNET) {
                throw new IllegalStateException("model file failed its checksum");
            }
            return backend(choice);
        });
        manager.apply(settings(MicrophoneSettings.NoiseBackend.AUTO));
        NoiseCancellationManager.Status status = await(manager);
        assertEquals(NoiseCancellationManager.Status.State.ACTIVE, status.state());
        assertEquals(MicrophoneSettings.NoiseBackend.DEEPFILTERNET, manager.activeBackend());
        assertEquals(List.of(MicrophoneSettings.NoiseBackend.DPDFNET, MicrophoneSettings.NoiseBackend.DEEPFILTERNET), tried);
        assertTrue(status.fallbacks().getFirst().contains("checksum"), status.fallbacks().toString());
        manager.close();
    }

    @Test
    void everythingFailingLeavesConventionalProcessingRunning() throws Exception {
        NoiseCancellationStage stage = new NoiseCancellationStage();
        NoiseCancellationManager manager = new NoiseCancellationManager(stage, (choice, progress) -> {
            throw new java.io.IOException("offline");
        });
        MicrophoneSettings s = settings(MicrophoneSettings.NoiseBackend.AUTO);
        manager.apply(s);
        NoiseCancellationManager.Status status = await(manager);
        assertEquals(NoiseCancellationManager.Status.State.UNAVAILABLE, status.state());
        assertTrue(status.detail().contains("conventional processing continues"));
        // The stage passes audio untouched and adds no latency.
        stage.configure(s);
        float[] block = new float[480];
        java.util.Arrays.fill(block, 0.25f);
        stage.process(block, 0, 480);
        assertTrue(!stage.isEnabled());
        assertEquals(0.25f, block[479]);
        manager.close();
    }

    @Test
    void turningOffUnloads() throws Exception {
        NoiseCancellationStage stage = new NoiseCancellationStage();
        NoiseCancellationManager manager = new NoiseCancellationManager(stage, (choice, progress) -> backend(choice));
        MicrophoneSettings s = settings(MicrophoneSettings.NoiseBackend.GTCRN);
        manager.apply(s);
        assertEquals(NoiseCancellationManager.Status.State.ACTIVE, await(manager).state());
        s.noise.level = MicrophoneSettings.NoiseLevel.OFF;
        manager.apply(s);
        assertEquals(NoiseCancellationManager.Status.State.OFF, manager.status().state());
        stage.process(new float[480], 0, 480);
        assertTrue(!stage.hasBackend());
        manager.close();
    }

    @Test
    void sustainedOverrunsDemoteToALighterModel() throws Exception {
        NoiseCancellationStage stage = new NoiseCancellationStage();
        NoiseCancellationManager manager = new NoiseCancellationManager(stage, (choice, progress) -> backend(choice));
        MicrophoneSettings s = settings(MicrophoneSettings.NoiseBackend.DPDFNET);
        manager.apply(s);
        await(manager);
        stage.configure(s);
        float[] block = new float[480];
        for (int i = 0; i < 200; i++) {
            stage.process(block, 0, 480);
        }
        stage.setOverloaded(true);
        for (int i = 0; i < 5; i++) {
            manager.supervise(s);
        }
        stage.setOverloaded(false);
        await(manager);
        assertEquals(MicrophoneSettings.NoiseBackend.DEEPFILTERNET, manager.activeBackend(),
                "a backend that cannot keep up is replaced by the next lighter one");
        manager.close();
    }
}
