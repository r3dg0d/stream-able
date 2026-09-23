package dev.streamable.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings for the microphone processing chain.
 *
 * <pre>
 *   Input gain -> DC removal / high-pass -> AI noise cancellation -> gate / expander
 *     -> EQ -> de-esser -> compressor -> automatic gain -> limiter -> output gain
 * </pre>
 *
 * <p>Every field here is consumed by a real DSP stage; nothing is decorative.
 * Mutable with public fields because this is a Gson-mapped object edited
 * directly by the UI; the DSP worker re-reads it when {@link #revision}
 * changes, and {@link #validate()} repairs out-of-range values.</p>
 */
public final class MicrophoneSettings {

    public enum Source {
        /** A capture device opened by Stream-able. */
        SYSTEM,
        /** The microphone signal Plasmo Voice already captured (and may have processed). */
        PLASMO_VOICE
    }

    public enum NoiseLevel { OFF, LIGHT, BALANCED, STRONG }

    public enum NoiseBackend {
        AUTO("Auto"),
        DPDFNET("DPDFNet (48 kHz)"),
        DEEPFILTERNET("DeepFilterNet2 (16 kHz)"),
        GTCRN("GTCRN (16 kHz, lightweight)");

        private final String displayName;

        NoiseBackend(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** Where the EQ sits relative to the compressor - the one reordering that is always safe. */
    public enum EqPlacement { BEFORE_COMPRESSOR, AFTER_COMPRESSOR }

    public static final class HighPass {
        public boolean enabled = true;
        /** Corner frequency. 80 Hz removes rumble without thinning normal voices. */
        public double frequencyHz = 80;
        /** 12 or 24 dB/octave. */
        public int slopeDbPerOctave = 12;
    }

    public static final class NoiseCancellation {
        public NoiseLevel level = NoiseLevel.OFF;
        public NoiseBackend backend = NoiseBackend.AUTO;
        /** When non-negative, overrides the level's maximum attenuation (dB). */
        public double strengthOverrideDb = -1;
        /** 0..1: share of the dry voice blended back in while speech is present. */
        public double voicePreservation = -1;
        /** Temporarily bypassed by hotkey or A/B; not persisted meaningfully. */
        public transient boolean bypass = false;
    }

    public static final class Gate {
        public boolean enabled = true;
        public double thresholdDb = -52;
        /** Hysteresis: the gate closes this many dB below the opening threshold. */
        public double hysteresisDb = 6;
        /** Maximum attenuation when closed. A range, not a mute: breaths stay natural. */
        public double rangeDb = 18;
        /** Downward-expansion ratio below the threshold (1:ratio). */
        public double ratio = 3;
        public double attackMs = 1.5;
        public double holdMs = 180;
        public double releaseMs = 220;
        /** Look-ahead so word beginnings are never clipped. */
        public double lookaheadMs = 3;
    }

    public enum BandType { LOW_SHELF, BELL, HIGH_SHELF, LOW_PASS }

    public static final class EqBand {
        public boolean enabled = true;
        public BandType type = BandType.BELL;
        public double frequencyHz = 1000;
        public double gainDb = 0;
        public double q = 1.0;

        public EqBand() {
        }

        public EqBand(BandType type, double frequencyHz, double gainDb, double q) {
            this.type = type;
            this.frequencyHz = frequencyHz;
            this.gainDb = gainDb;
            this.q = q;
        }
    }

    public static final class Equalizer {
        public boolean enabled = true;
        public String preset = "Clear Voice";
        public List<EqBand> bands = defaultBands();

        public static List<EqBand> defaultBands() {
            List<EqBand> bands = new ArrayList<>();
            bands.add(new EqBand(BandType.LOW_SHELF, 120, 0, 0.7));
            bands.add(new EqBand(BandType.BELL, 300, -1.5, 1.0));
            bands.add(new EqBand(BandType.BELL, 3000, 1.5, 1.0));
            bands.add(new EqBand(BandType.BELL, 5500, 0, 2.0));
            bands.add(new EqBand(BandType.HIGH_SHELF, 10000, 1.0, 0.7));
            return bands;
        }
    }

    public static final class DeEsser {
        public boolean enabled = false;
        /** Automatically centres detection in the 5-8 kHz sibilance region. */
        public boolean auto = true;
        public double frequencyHz = 6500;
        public double thresholdDb = -28;
        /** Maximum reduction of the sibilant band, in dB. */
        public double amountDb = 6;
    }

    public static final class Compressor {
        public boolean enabled = true;
        public String preset = "Natural";
        public double thresholdDb = -20;
        public double ratio = 3;
        public double attackMs = 8;
        public double releaseMs = 140;
        public double kneeDb = 6;
        public double makeupDb = 0;
        public boolean autoMakeup = true;
    }

    public static final class AutomaticGain {
        public boolean enabled = false;
        /** Long-term speech level the AGC steers toward. */
        public double targetDb = -20;
        public double maxGainDb = 12;
        /** How fast gain may change, in dB per second. */
        public double speedDbPerSecond = 1.5;
    }

    public static final class Limiter {
        public boolean enabled = true;
        /** Kept below 0 dBFS so encoder and resampler overshoot never clips. */
        public double ceilingDb = -1.0;
        public double releaseMs = 60;
    }

    /** A named, saved whole-chain configuration. */
    public static final class CustomPreset {
        public String name = "";
        /** The chain, as the same JSON this class serialises to. */
        public String chainJson = "";
    }

    // ---- source & gain -------------------------------------------------------

    public Source source = Source.SYSTEM;
    /** Run Stream-able's chain on the Plasmo Voice signal too. */
    public boolean processPlasmoVoice = false;
    /** Digital gain applied first, in dB. Calibration sets this. */
    public double inputGainDb = 0;
    /** Final gain after the limiter, in dB. */
    public double outputGainDb = 0;
    /** Master bypass: the raw signal goes to the mix. */
    public boolean processingEnabled = true;
    public String preset = "Streaming";
    public boolean advancedMode = false;
    public EqPlacement eqPlacement = EqPlacement.BEFORE_COMPRESSOR;

    public HighPass highPass = new HighPass();
    public NoiseCancellation noise = new NoiseCancellation();
    public Gate gate = new Gate();
    public Equalizer eq = new Equalizer();
    public DeEsser deEsser = new DeEsser();
    public Compressor compressor = new Compressor();
    public AutomaticGain agc = new AutomaticGain();
    public Limiter limiter = new Limiter();

    // ---- monitoring & control ------------------------------------------------

    /** Live monitoring to the default output. Never persisted: it always starts off, since it can feed back. */
    public transient boolean monitoring = false;
    /** Monitor the processed (true) or raw (false) signal. */
    public boolean monitorProcessed = true;
    public double monitorVolume = 0.8;
    public boolean muted = false;
    /** When true the microphone only reaches the mix while the push-to-talk key is held. */
    public boolean pushToTalk = false;
    /** Release delay after the push-to-talk key, so the last word is not cut. */
    public double pushToTalkReleaseMs = 250;

    public List<CustomPreset> customPresets = new ArrayList<>();

    /** Bumped on every change so the DSP worker knows to reconfigure. Not persisted. */
    public transient volatile int revision;

    public void touch() {
        revision++;
    }

    public void validate() {
        if (source == null) {
            source = Source.SYSTEM;
        }
        if (eqPlacement == null) {
            eqPlacement = EqPlacement.BEFORE_COMPRESSOR;
        }
        if (highPass == null) {
            highPass = new HighPass();
        }
        if (noise == null) {
            noise = new NoiseCancellation();
        }
        if (gate == null) {
            gate = new Gate();
        }
        if (eq == null) {
            eq = new Equalizer();
        }
        if (deEsser == null) {
            deEsser = new DeEsser();
        }
        if (compressor == null) {
            compressor = new Compressor();
        }
        if (agc == null) {
            agc = new AutomaticGain();
        }
        if (limiter == null) {
            limiter = new Limiter();
        }
        if (customPresets == null) {
            customPresets = new ArrayList<>();
        }
        customPresets.removeIf(p -> p == null || p.name == null || p.name.isBlank());
        if (noise.level == null) {
            noise.level = NoiseLevel.OFF;
        }
        if (noise.backend == null) {
            noise.backend = NoiseBackend.AUTO;
        }
        if (eq.bands == null || eq.bands.isEmpty()) {
            eq.bands = Equalizer.defaultBands();
        }
        eq.bands.removeIf(b -> b == null);
        while (eq.bands.size() > 8) {
            eq.bands.removeLast();
        }
        for (EqBand band : eq.bands) {
            if (band.type == null) {
                band.type = BandType.BELL;
            }
            band.frequencyHz = clamp(band.frequencyHz, 20, 20000);
            band.gainDb = clamp(band.gainDb, -18, 18);
            band.q = clamp(band.q, 0.1, 12);
        }
        inputGainDb = clamp(inputGainDb, -24, 36);
        outputGainDb = clamp(outputGainDb, -24, 24);
        highPass.frequencyHz = clamp(highPass.frequencyHz, 20, 400);
        highPass.slopeDbPerOctave = highPass.slopeDbPerOctave >= 24 ? 24 : 12;
        noise.voicePreservation = noise.voicePreservation < 0 ? -1 : clamp(noise.voicePreservation, 0, 1);
        noise.strengthOverrideDb = noise.strengthOverrideDb < 0 ? -1 : clamp(noise.strengthOverrideDb, 0, 100);
        gate.thresholdDb = clamp(gate.thresholdDb, -90, -10);
        gate.hysteresisDb = clamp(gate.hysteresisDb, 0, 20);
        gate.rangeDb = clamp(gate.rangeDb, 0, 80);
        gate.ratio = clamp(gate.ratio, 1, 20);
        gate.attackMs = clamp(gate.attackMs, 0.1, 50);
        gate.holdMs = clamp(gate.holdMs, 0, 1000);
        gate.releaseMs = clamp(gate.releaseMs, 5, 2000);
        gate.lookaheadMs = clamp(gate.lookaheadMs, 0, 10);
        deEsser.frequencyHz = clamp(deEsser.frequencyHz, 2000, 12000);
        deEsser.thresholdDb = clamp(deEsser.thresholdDb, -60, 0);
        deEsser.amountDb = clamp(deEsser.amountDb, 0, 18);
        compressor.thresholdDb = clamp(compressor.thresholdDb, -60, 0);
        compressor.ratio = clamp(compressor.ratio, 1, 20);
        compressor.attackMs = clamp(compressor.attackMs, 0.1, 200);
        compressor.releaseMs = clamp(compressor.releaseMs, 10, 2000);
        compressor.kneeDb = clamp(compressor.kneeDb, 0, 24);
        compressor.makeupDb = clamp(compressor.makeupDb, -12, 24);
        agc.targetDb = clamp(agc.targetDb, -40, -6);
        agc.maxGainDb = clamp(agc.maxGainDb, 0, 30);
        agc.speedDbPerSecond = clamp(agc.speedDbPerSecond, 0.1, 12);
        limiter.ceilingDb = clamp(limiter.ceilingDb, -12, -0.1);
        limiter.releaseMs = clamp(limiter.releaseMs, 5, 1000);
        monitorVolume = clamp(monitorVolume, 0, 2);
        pushToTalkReleaseMs = clamp(pushToTalkReleaseMs, 0, 2000);
        touch();
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.clamp(value, min, max);
    }
}
