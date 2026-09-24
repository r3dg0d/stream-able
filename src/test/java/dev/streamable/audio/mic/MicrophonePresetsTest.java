package dev.streamable.audio.mic;

import dev.streamable.config.MicrophoneSettings;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrophonePresetsTest {

    @Test
    void everyRequiredPresetExistsAndConfiguresRealParameters() {
        List<String> required = List.of("Clean", "Streaming", "Podcast", "Noisy Room", "Keyboard Noise",
                "Laptop Mic", "Dynamic Mic", "Condenser Mic");
        Set<String> signatures = new HashSet<>();
        for (String name : required) {
            MicrophoneSettings s = new MicrophoneSettings();
            assertTrue(MicrophonePresets.applyChain(name, s), name);
            signatures.add(MicrophonePresets.exportChain(s));
        }
        assertEquals(required.size(), signatures.size(), "each preset produces a different chain");
        assertTrue(MicrophonePresets.GATE.keySet().containsAll(List.of("Quiet Room", "Typical Bedroom",
                "Mechanical Keyboard", "Loud PC Fans")));
        assertTrue(MicrophonePresets.EQ.keySet().containsAll(List.of("Flat", "Warm", "Clear Voice", "Broadcast",
                "Reduce Boom", "Reduce Harshness")));
        assertTrue(MicrophonePresets.COMPRESSOR.keySet().containsAll(List.of("Natural", "Broadcast", "Loud", "Podcast")));
    }

    @Test
    void noiseLevelsDifferBetweenPresets() {
        MicrophoneSettings clean = new MicrophoneSettings();
        MicrophonePresets.applyChain("Clean", clean);
        MicrophoneSettings noisy = new MicrophoneSettings();
        MicrophonePresets.applyChain("Noisy Room", noisy);
        assertEquals(MicrophoneSettings.NoiseLevel.OFF, clean.noise.level);
        assertEquals(MicrophoneSettings.NoiseLevel.STRONG, noisy.noise.level);
    }

    @Test
    void customPresetsRoundTripAndArePortable() {
        MicrophoneSettings s = new MicrophoneSettings();
        MicrophonePresets.applyChain("Podcast", s);
        s.compressor.ratio = 7.5;
        s.gate.thresholdDb = -47;
        MicrophonePresets.saveCustom("My Voice", s);
        String exported = MicrophonePresets.exportChain(s);

        MicrophoneSettings other = new MicrophoneSettings();
        MicrophonePresets.importChain(exported, other);
        assertEquals(7.5, other.compressor.ratio);
        assertEquals(-47, other.gate.thresholdDb);

        MicrophonePresets.applyChain("Clean", s);
        assertTrue(MicrophonePresets.applyChain("My Voice", s));
        assertEquals(7.5, s.compressor.ratio);
        assertThrows(IllegalArgumentException.class, () -> MicrophonePresets.saveCustom("Streaming", s));
    }

    @Test
    void resetStageAndChain() {
        MicrophoneSettings s = new MicrophoneSettings();
        s.limiter.ceilingDb = -6;
        MicrophonePresets.resetStage("limiter", s);
        assertEquals(-1.0, s.limiter.ceilingDb);
        s.gate.thresholdDb = -20;
        MicrophonePresets.resetChain(s);
        assertEquals("Streaming", s.preset);
    }
}
