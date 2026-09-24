package dev.streamable.audio.mic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.streamable.config.MicrophoneSettings;
import dev.streamable.config.MicrophoneSettings.BandType;
import dev.streamable.config.MicrophoneSettings.EqBand;
import dev.streamable.config.MicrophoneSettings.NoiseLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Presets that configure real parameters - whole chains and individual stages.
 *
 * <p>Whole-chain presets set every stage (and leave the device, input gain,
 * monitoring and hotkeys alone, since those belong to the setup, not the
 * sound). Custom presets are the same JSON as the settings, so they can be
 * copied between installations.</p>
 */
public final class MicrophonePresets {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private MicrophonePresets() {
    }

    // ---- stage presets -----------------------------------------------------------

    public static final Map<String, Consumer<MicrophoneSettings.Gate>> GATE = new LinkedHashMap<>();
    public static final Map<String, List<EqBand>> EQ = new LinkedHashMap<>();
    public static final Map<String, Consumer<MicrophoneSettings.Compressor>> COMPRESSOR = new LinkedHashMap<>();
    public static final Map<String, Consumer<MicrophoneSettings>> CHAIN = new LinkedHashMap<>();

    static {
        GATE.put("Quiet Room", g -> gate(g, -60, 12, 2, 250));
        GATE.put("Typical Bedroom", g -> gate(g, -52, 18, 3, 220));
        GATE.put("Mechanical Keyboard", g -> gate(g, -44, 20, 4, 160));
        GATE.put("Loud PC Fans", g -> gate(g, -40, 15, 3, 250));

        EQ.put("Flat", List.of());
        EQ.put("Warm", List.of(band(BandType.LOW_SHELF, 150, 2.5, 0.7), band(BandType.BELL, 3500, -1, 1.0),
                band(BandType.HIGH_SHELF, 9000, -1.5, 0.7)));
        EQ.put("Clear Voice", List.of(band(BandType.BELL, 300, -1.5, 1.0), band(BandType.BELL, 3000, 1.5, 1.0),
                band(BandType.HIGH_SHELF, 10000, 1.0, 0.7)));
        EQ.put("Broadcast", List.of(band(BandType.LOW_SHELF, 120, 1.5, 0.7), band(BandType.BELL, 250, -2, 1.2),
                band(BandType.BELL, 3200, 2.5, 0.9), band(BandType.HIGH_SHELF, 11000, 2, 0.7)));
        EQ.put("Reduce Boom", List.of(band(BandType.LOW_SHELF, 180, -4, 0.7), band(BandType.BELL, 280, -2.5, 1.2)));
        EQ.put("Reduce Harshness", List.of(band(BandType.BELL, 3500, -3, 1.4), band(BandType.BELL, 6500, -2, 2.0)));

        COMPRESSOR.put("Natural", c -> compressor(c, -20, 2.5, 10, 160, 8));
        COMPRESSOR.put("Broadcast", c -> compressor(c, -24, 4, 5, 120, 6));
        COMPRESSOR.put("Loud", c -> compressor(c, -28, 6, 3, 90, 4));
        COMPRESSOR.put("Podcast", c -> compressor(c, -22, 3, 15, 220, 10));

        CHAIN.put("Clean", s -> {
            base(s, 80, NoiseLevel.OFF, "Quiet Room", "Flat", "Natural");
            s.deEsser.enabled = false;
            s.compressor.enabled = false;
        });
        CHAIN.put("Streaming", s -> base(s, 80, NoiseLevel.BALANCED, "Typical Bedroom", "Clear Voice", "Broadcast"));
        CHAIN.put("Podcast", s -> {
            base(s, 70, NoiseLevel.LIGHT, "Quiet Room", "Broadcast", "Podcast");
            s.deEsser.enabled = true;
            s.agc.enabled = true;
        });
        CHAIN.put("Noisy Room", s -> base(s, 100, NoiseLevel.STRONG, "Loud PC Fans", "Clear Voice", "Broadcast"));
        CHAIN.put("Keyboard Noise", s -> base(s, 90, NoiseLevel.BALANCED, "Mechanical Keyboard", "Clear Voice", "Broadcast"));
        CHAIN.put("Laptop Mic", s -> {
            base(s, 120, NoiseLevel.STRONG, "Loud PC Fans", "Reduce Boom", "Loud");
            s.agc.enabled = true;
        });
        CHAIN.put("Dynamic Mic", s -> {
            base(s, 70, NoiseLevel.LIGHT, "Typical Bedroom", "Broadcast", "Natural");
            s.deEsser.enabled = false;
        });
        CHAIN.put("Condenser Mic", s -> {
            base(s, 90, NoiseLevel.BALANCED, "Typical Bedroom", "Reduce Harshness", "Broadcast");
            s.deEsser.enabled = true;
        });
    }

    private static void gate(MicrophoneSettings.Gate g, double threshold, double range, double ratio, double release) {
        g.enabled = true;
        g.thresholdDb = threshold;
        g.rangeDb = range;
        g.ratio = ratio;
        g.releaseMs = release;
        g.attackMs = 1.5;
        g.holdMs = 180;
        g.hysteresisDb = 6;
        g.lookaheadMs = 3;
    }

    private static void compressor(MicrophoneSettings.Compressor c, double threshold, double ratio,
                                   double attack, double release, double knee) {
        c.enabled = true;
        c.thresholdDb = threshold;
        c.ratio = ratio;
        c.attackMs = attack;
        c.releaseMs = release;
        c.kneeDb = knee;
        c.autoMakeup = true;
    }

    private static EqBand band(BandType type, double frequency, double gain, double q) {
        return new EqBand(type, frequency, gain, q);
    }

    private static void base(MicrophoneSettings s, double highPass, NoiseLevel noise, String gate, String eq, String comp) {
        s.processingEnabled = true;
        s.highPass.enabled = true;
        s.highPass.frequencyHz = highPass;
        s.highPass.slopeDbPerOctave = 12;
        s.noise.level = noise;
        s.noise.strengthOverrideDb = -1;
        s.noise.voicePreservation = -1;
        applyGate(gate, s);
        applyEq(eq, s);
        applyCompressor(comp, s);
        s.deEsser.enabled = false;
        s.deEsser.auto = true;
        s.agc.enabled = false;
        s.limiter.enabled = true;
        s.limiter.ceilingDb = -1.0;
        s.limiter.releaseMs = 60;
        s.outputGainDb = 0;
        s.eqPlacement = MicrophoneSettings.EqPlacement.BEFORE_COMPRESSOR;
    }

    public static void applyGate(String name, MicrophoneSettings s) {
        Consumer<MicrophoneSettings.Gate> preset = GATE.get(name);
        if (preset != null) {
            preset.accept(s.gate);
            s.touch();
        }
    }

    public static void applyEq(String name, MicrophoneSettings s) {
        List<EqBand> preset = EQ.get(name);
        if (preset == null) {
            return;
        }
        List<EqBand> bands = new ArrayList<>();
        for (EqBand band : preset) {
            bands.add(new EqBand(band.type, band.frequencyHz, band.gainDb, band.q));
        }
        if (bands.isEmpty()) {
            bands.add(new EqBand(BandType.BELL, 1000, 0, 1.0));
        }
        s.eq.enabled = true;
        s.eq.preset = name;
        s.eq.bands = bands;
        s.touch();
    }

    public static void applyCompressor(String name, MicrophoneSettings s) {
        Consumer<MicrophoneSettings.Compressor> preset = COMPRESSOR.get(name);
        if (preset != null) {
            preset.accept(s.compressor);
            s.compressor.preset = name;
            s.touch();
        }
    }

    /** Applies a built-in or custom whole-chain preset. */
    public static boolean applyChain(String name, MicrophoneSettings s) {
        Consumer<MicrophoneSettings> preset = CHAIN.get(name);
        if (preset != null) {
            preset.accept(s);
            s.preset = name;
            s.validate();
            return true;
        }
        for (MicrophoneSettings.CustomPreset custom : s.customPresets) {
            if (custom.name.equals(name)) {
                importChain(custom.chainJson, s);
                s.preset = name;
                return true;
            }
        }
        return false;
    }

    /** Resets the whole chain to the default "Streaming" sound. */
    public static void resetChain(MicrophoneSettings s) {
        applyChain("Streaming", s);
    }

    /** Resets one stage to its default. */
    public static void resetStage(String stageId, MicrophoneSettings s) {
        MicrophoneSettings defaults = new MicrophoneSettings();
        switch (stageId) {
            case "highpass" -> s.highPass = defaults.highPass;
            case "ai" -> s.noise = defaults.noise;
            case "gate" -> s.gate = defaults.gate;
            case "eq" -> s.eq = defaults.eq;
            case "deesser" -> s.deEsser = defaults.deEsser;
            case "compressor" -> s.compressor = defaults.compressor;
            case "agc" -> s.agc = defaults.agc;
            case "limiter" -> s.limiter = defaults.limiter;
            default -> {
                return;
            }
        }
        s.validate();
    }

    /** The chain part of the settings as portable JSON (no device, gain, hotkeys or presets list). */
    public static String exportChain(MicrophoneSettings s) {
        JsonObject json = new JsonObject();
        json.addProperty("format", "stream-able-mic-chain/1");
        json.add("highPass", GSON.toJsonTree(s.highPass));
        json.add("noise", GSON.toJsonTree(s.noise));
        json.add("gate", GSON.toJsonTree(s.gate));
        json.add("eq", GSON.toJsonTree(s.eq));
        json.add("deEsser", GSON.toJsonTree(s.deEsser));
        json.add("compressor", GSON.toJsonTree(s.compressor));
        json.add("agc", GSON.toJsonTree(s.agc));
        json.add("limiter", GSON.toJsonTree(s.limiter));
        json.addProperty("outputGainDb", s.outputGainDb);
        json.addProperty("eqPlacement", s.eqPlacement.name());
        return GSON.toJson(json);
    }

    /** Applies exported chain JSON; unknown or missing parts keep their current values. */
    public static void importChain(String text, MicrophoneSettings s) {
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        if (json.has("highPass")) {
            s.highPass = GSON.fromJson(json.get("highPass"), MicrophoneSettings.HighPass.class);
        }
        if (json.has("noise")) {
            s.noise = GSON.fromJson(json.get("noise"), MicrophoneSettings.NoiseCancellation.class);
        }
        if (json.has("gate")) {
            s.gate = GSON.fromJson(json.get("gate"), MicrophoneSettings.Gate.class);
        }
        if (json.has("eq")) {
            s.eq = GSON.fromJson(json.get("eq"), MicrophoneSettings.Equalizer.class);
        }
        if (json.has("deEsser")) {
            s.deEsser = GSON.fromJson(json.get("deEsser"), MicrophoneSettings.DeEsser.class);
        }
        if (json.has("compressor")) {
            s.compressor = GSON.fromJson(json.get("compressor"), MicrophoneSettings.Compressor.class);
        }
        if (json.has("agc")) {
            s.agc = GSON.fromJson(json.get("agc"), MicrophoneSettings.AutomaticGain.class);
        }
        if (json.has("limiter")) {
            s.limiter = GSON.fromJson(json.get("limiter"), MicrophoneSettings.Limiter.class);
        }
        if (json.has("outputGainDb")) {
            s.outputGainDb = json.get("outputGainDb").getAsDouble();
        }
        if (json.has("eqPlacement")) {
            try {
                s.eqPlacement = MicrophoneSettings.EqPlacement.valueOf(json.get("eqPlacement").getAsString());
            } catch (IllegalArgumentException ignored) {
                // keep current placement
            }
        }
        s.validate();
    }

    /** Saves (or replaces) a named custom preset from the current chain. */
    public static void saveCustom(String name, MicrophoneSettings s) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || CHAIN.containsKey(trimmed)) {
            throw new IllegalArgumentException("Choose a name that is not a built-in preset.");
        }
        s.customPresets.removeIf(p -> p.name.equals(trimmed));
        MicrophoneSettings.CustomPreset preset = new MicrophoneSettings.CustomPreset();
        preset.name = trimmed;
        preset.chainJson = exportChain(s);
        s.customPresets.add(preset);
        s.preset = trimmed;
        s.touch();
    }

    public static void deleteCustom(String name, MicrophoneSettings s) {
        s.customPresets.removeIf(p -> p.name.equals(name));
        s.touch();
    }
}
