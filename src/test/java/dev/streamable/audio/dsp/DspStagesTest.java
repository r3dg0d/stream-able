package dev.streamable.audio.dsp;

import dev.streamable.config.MicrophoneSettings;
import org.junit.jupiter.api.Test;

import static dev.streamable.audio.dsp.Signals.SR;
import static dev.streamable.audio.dsp.Signals.concat;
import static dev.streamable.audio.dsp.Signals.constant;
import static dev.streamable.audio.dsp.Signals.peak;
import static dev.streamable.audio.dsp.Signals.rmsDb;
import static dev.streamable.audio.dsp.Signals.run;
import static dev.streamable.audio.dsp.Signals.sine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DspStagesTest {

    private static MicrophoneSettings settings() {
        MicrophoneSettings s = new MicrophoneSettings();
        s.validate();
        return s;
    }

    // ---- gain ----------------------------------------------------------------

    @Test
    void digitalGainIsExactAfterTheRamp() {
        MicrophoneSettings s = settings();
        s.inputGainDb = 6;
        GainStage gain = new GainStage(GainStage.Position.INPUT, SR);
        gain.configure(s);
        float[] out = run(gain, constant(0.1, SR / 2));
        assertEquals(0.1 * Db.toLinear(6), out[out.length - 1], 1e-6);
        assertTrue(Math.abs(out[1]) < Math.abs(out[out.length - 1]), "gain changes are ramped, not stepped");
    }

    // ---- DC / high-pass ------------------------------------------------------------

    @Test
    void dcIsRemoved() {
        MicrophoneSettings s = settings();
        s.highPass.enabled = false;
        HighPassStage stage = new HighPassStage(SR);
        stage.configure(s);
        float[] out = run(stage, constant(0.2, SR * 2));
        assertTrue(Math.abs(out[out.length - 1]) < 1e-3, "DC decays to zero: " + out[out.length - 1]);
    }

    @Test
    void highPassAttenuatesRumbleAndKeepsVoice() {
        MicrophoneSettings s = settings();
        s.highPass.frequencyHz = 80;
        HighPassStage stage = new HighPassStage(SR);
        stage.configure(s);
        double rumble = rmsDb(run(stage, sine(20, 0.5, SR)), SR / 2, SR) - rmsDb(sine(20, 0.5, SR), SR / 2, SR);
        stage.reset();
        double voice = rmsDb(run(stage, sine(300, 0.5, SR)), SR / 2, SR) - rmsDb(sine(300, 0.5, SR), SR / 2, SR);
        assertTrue(rumble < -20, "20 Hz should be well down at 12 dB/oct: " + rumble);
        assertTrue(voice > -0.5, "300 Hz voice fundamental must be untouched: " + voice);

        s.highPass.slopeDbPerOctave = 24;
        stage.configure(s);
        stage.reset();
        double steeper = rmsDb(run(stage, sine(20, 0.5, SR)), SR / 2, SR) - rmsDb(sine(20, 0.5, SR), SR / 2, SR);
        assertTrue(steeper < rumble - 15, "24 dB/oct is steeper: " + steeper);
    }

    // ---- gate --------------------------------------------------------------------

    private static GateStage gate(MicrophoneSettings s) {
        GateStage gate = new GateStage(SR);
        gate.configure(s);
        return gate;
    }

    @Test
    void gateAttenuatesBelowThresholdByAtMostTheRange() {
        MicrophoneSettings s = settings();
        s.gate.thresholdDb = -40;
        s.gate.rangeDb = 18;
        float[] quiet = sine(200, Db.toLinear(-70), SR);
        float[] out = run(gate(s), quiet);
        double reduction = rmsDb(quiet, SR / 2, SR) - rmsDb(out, SR / 2, SR);
        assertEquals(18, reduction, 1.0, "room tone lowered by the range, not muted");
    }

    @Test
    void gatePassesSpeechAboveThreshold() {
        MicrophoneSettings s = settings();
        s.gate.thresholdDb = -40;
        float[] speech = sine(200, Db.toLinear(-20), SR);
        float[] out = run(gate(s), speech);
        assertEquals(rmsDb(speech, SR / 2, SR), rmsDb(out, SR / 2, SR), 0.2);
    }

    @Test
    void lookaheadMeansWordBeginningsAreNotClipped() {
        MicrophoneSettings s = settings();
        s.gate.thresholdDb = -40;
        s.gate.attackMs = 1;
        s.gate.lookaheadMs = 3;
        int silence = SR / 2;
        float[] signal = concat(new float[silence], sine(1000, 0.3, SR / 10));
        GateStage g = gate(s);
        float[] out = run(g, signal);
        int delay = g.latencySamples();
        // The first 2 ms of the "word" (after the look-ahead delay) keep ~all of their level.
        double onset = rmsDb(out, silence + delay, silence + delay + 96);
        double original = rmsDb(signal, silence, silence + 96);
        assertTrue(onset > original - 1.5, "onset kept: " + onset + " vs " + original);
    }

    @Test
    void holdKeepsTheGateOpenThroughShortPauses() {
        MicrophoneSettings s = settings();
        s.gate.thresholdDb = -40;
        s.gate.holdMs = 200;
        s.gate.rangeDb = 40;
        float[] signal = concat(sine(300, 0.2, SR / 2), new float[SR / 10], sine(300, 0.2, SR / 4));
        GateStage g = gate(s);
        run(g, signal);
        assertEquals(1, g.openings(), "a 100 ms pause inside the hold time must not re-trigger the gate");
    }

    @Test
    void releaseIsGradual() {
        MicrophoneSettings s = settings();
        s.gate.thresholdDb = -40;
        s.gate.holdMs = 0;
        s.gate.releaseMs = 200;
        s.gate.rangeDb = 40;
        s.gate.lookaheadMs = 0;
        float[] tail = sine(300, Db.toLinear(-60), SR / 2);
        GateStage g = gate(s);
        float[] out = run(g, concat(sine(300, 0.2, SR / 4), tail));
        int start = SR / 4;
        double early = rmsDb(out, start + 480, start + 960) - rmsDb(tail, 480, 960);
        double late = rmsDb(out, start + SR / 4, start + SR / 4 + 480) - rmsDb(tail, SR / 4, SR / 4 + 480);
        assertTrue(early > late + 3, "attenuation grows over the release, it does not snap shut: " + early + " / " + late);
    }

    @Test
    void hysteresisPreventsChatter() {
        MicrophoneSettings s = settings();
        s.gate.thresholdDb = -40;
        s.gate.holdMs = 0;
        s.gate.hysteresisDb = 6;
        // A level wobbling +-2 dB around the threshold.
        float[] wobble = new float[SR * 2];
        for (int i = 0; i < wobble.length; i++) {
            double levelDb = -40 + 2 * Math.sin(2 * Math.PI * 4 * i / SR);
            wobble[i] = (float) (Db.toLinear(levelDb) * Math.sin(2 * Math.PI * 500 * i / SR));
        }
        GateStage g = gate(s);
        run(g, wobble);
        assertTrue(g.openings() <= 1, "opened " + g.openings() + " times");
        s.gate.hysteresisDb = 0;
        GateStage noHysteresis = gate(s);
        run(noHysteresis, wobble);
        assertTrue(noHysteresis.openings() > g.openings());
    }

    // ---- EQ ------------------------------------------------------------------

    @Test
    void peakingCoefficientsHaveTheRequestedGainAtCentre() {
        var c = BiquadDesign.peaking(3000, 6, 1, SR);
        assertEquals(6, c.magnitudeDb(3000, SR), 0.01);
        assertEquals(0, c.magnitudeDb(50, SR), 0.1);
        assertTrue(c.isStable());
        assertEquals(-3.01, BiquadDesign.highPass(80, BiquadDesign.BUTTERWORTH_2_Q, SR).magnitudeDb(80, SR), 0.05);
        assertEquals(4, BiquadDesign.highShelf(8000, 4, 0.7, SR).magnitudeDb(20000, SR), 0.3);
        assertEquals(-5, BiquadDesign.lowShelf(100, -5, 0.7, SR).magnitudeDb(25, SR), 0.3);
    }

    @Test
    void eqAppliesTheGraphedResponse() {
        MicrophoneSettings s = settings();
        s.eq.bands = java.util.List.of(new MicrophoneSettings.EqBand(MicrophoneSettings.BandType.BELL, 2000, 6, 1.0));
        EqualizerStage eq = new EqualizerStage(SR);
        eq.configure(s);
        float[] in = sine(2000, 0.1, SR);
        double measured = rmsDb(run(eq, in), SR / 2, SR) - rmsDb(in, SR / 2, SR);
        assertEquals(EqualizerStage.responseDb(s.eq, 2000, SR), measured, 0.1);
        assertEquals(6, measured, 0.1);
    }

    @Test
    void disabledOrFlatEqIsBitExact() {
        MicrophoneSettings s = settings();
        s.eq.bands = java.util.List.of(new MicrophoneSettings.EqBand(MicrophoneSettings.BandType.BELL, 2000, 0, 1.0));
        EqualizerStage eq = new EqualizerStage(SR);
        eq.configure(s);
        float[] in = Signals.noise(0.3, 4800, 1);
        org.junit.jupiter.api.Assertions.assertArrayEquals(in, run(eq, in));
        assertEquals(0, EqualizerStage.responseDb(s.eq, 1234, SR), 1e-9);
    }

    // ---- de-esser ----------------------------------------------------------------

    @Test
    void deEsserReducesSibilanceButNotVoice() {
        MicrophoneSettings s = settings();
        s.deEsser.enabled = true;
        s.deEsser.thresholdDb = -30;
        s.deEsser.amountDb = 8;
        DeEsserStage stage = new DeEsserStage(SR);
        stage.configure(s);
        float[] hiss = sine(7000, 0.3, SR / 2);
        double reduction = rmsDb(hiss, SR / 4, SR / 2) - rmsDb(run(stage, hiss), SR / 4, SR / 2);
        stage.reset();
        float[] vowel = sine(250, 0.3, SR / 2);
        double vowelChange = rmsDb(vowel, SR / 4, SR / 2) - rmsDb(run(stage, vowel), SR / 4, SR / 2);
        assertTrue(reduction > 3, "sibilance reduced: " + reduction);
        assertTrue(Math.abs(vowelChange) < 0.3, "voiced speech untouched: " + vowelChange);
    }

    @Test
    void idleDeEsserIsTransparent() {
        MicrophoneSettings s = settings();
        s.deEsser.enabled = true;
        s.deEsser.thresholdDb = 0;   // never triggers
        DeEsserStage stage = new DeEsserStage(SR);
        stage.configure(s);
        float[] in = Signals.noise(0.1, 4800, 5);
        float[] out = run(stage, in);
        for (int i = 0; i < in.length; i++) {
            assertEquals(in[i], out[i], 1e-6, "perfect reconstruction when not reducing");
        }
    }

    // ---- compressor ---------------------------------------------------------------

    @Test
    void compressorStaticCurve() {
        assertEquals(0, CompressorStage.staticReductionDb(-30, -20, 4, 0), 1e-9);
        assertEquals(7.5, CompressorStage.staticReductionDb(-10, -20, 4, 0), 1e-9, "10 dB over at 4:1 -> 7.5 dB");
        double atThreshold = CompressorStage.staticReductionDb(-20, -20, 4, 6);
        assertTrue(atThreshold > 0 && atThreshold < 1.5, "soft knee starts gently: " + atThreshold);
        assertEquals(5, CompressorStage.autoMakeupDb(-20, 2), 1e-9);
    }

    @Test
    void compressorReducesLoudSignalsByTheRatio() {
        MicrophoneSettings s = settings();
        s.compressor.thresholdDb = -30;
        s.compressor.ratio = 4;
        s.compressor.kneeDb = 0;
        s.compressor.autoMakeup = false;
        s.compressor.makeupDb = 0;
        CompressorStage stage = new CompressorStage(SR);
        stage.configure(s);
        float[] loud = sine(500, Db.toLinear(-10), SR);
        double outDb = rmsDb(run(stage, loud), SR / 2, SR) + 3.01;   // peak-referred
        // Input peak -10 dB: 20 over, 15 reduced -> about -25 dB peak.
        assertEquals(-25, outDb, 1.5);
    }

    @Test
    void attackAndReleaseShapeTheEnvelope() {
        MicrophoneSettings s = settings();
        s.compressor.thresholdDb = -30;
        s.compressor.ratio = 10;
        s.compressor.attackMs = 20;
        s.compressor.releaseMs = 300;
        s.compressor.autoMakeup = false;
        CompressorStage stage = new CompressorStage(SR);
        stage.configure(s);
        float[] burst = sine(500, 0.5, SR / 2);
        float[] data = burst.clone();
        stage.process(data, 0, 240);                          // 5 ms: still attacking
        double early = stage.gainReductionDb();
        stage.process(data, 240, SR / 2 - 240);
        double settled = stage.gainReductionDb();
        assertTrue(early < settled * 0.6, "attack takes time: " + early + " vs " + settled);
        float[] quiet = new float[SR / 10];
        stage.process(quiet, 0, quiet.length);                // 100 ms into a 300 ms release
        assertTrue(stage.gainReductionDb() > settled * 0.3, "release is gradual");
    }

    // ---- AGC -------------------------------------------------------------------

    @Test
    void agcMovesSlowlyTowardTheTarget() {
        MicrophoneSettings s = settings();
        s.agc.enabled = true;
        s.agc.targetDb = -20;
        s.agc.speedDbPerSecond = 2;
        AgcStage agc = new AgcStage(SR);
        agc.configure(s);
        // Establish a noise floor, then quiet speech at -32 dB RMS.
        run(agc, Signals.noise(Db.toLinear(-75), SR * 2, 3));
        float[] speech = sine(300, Db.toLinear(-32 + 3.01), SR * 4);
        run(agc, speech);
        double afterFour = agc.currentGainDb();
        assertTrue(afterFour > 5 && afterFour < 9, "about 2 dB/s for 4 s: " + afterFour);
        run(agc, sine(300, Db.toLinear(-32 + 3.01), SR * 6));
        assertEquals(12, agc.currentGainDb(), 0.5, "converges on the target and stops");
    }

    @Test
    void agcDoesNotChaseSilence() {
        MicrophoneSettings s = settings();
        s.agc.enabled = true;
        AgcStage agc = new AgcStage(SR);
        agc.configure(s);
        run(agc, Signals.noise(Db.toLinear(-70), SR * 10, 9));
        assertEquals(0, agc.currentGainDb(), 1e-9, "room tone never gets boosted");
    }

    // ---- limiter ----------------------------------------------------------------

    @Test
    void limiterNeverExceedsTheCeiling() {
        MicrophoneSettings s = settings();
        s.limiter.ceilingDb = -1;
        LimiterStage limiter = new LimiterStage(SR);
        limiter.configure(s);
        // A yell / clap: +12 dB over full scale with a sharp transient.
        float[] yell = concat(sine(400, 0.1, SR / 10), sine(400, 4.0, SR / 5), sine(400, 0.1, SR / 10));
        float[] out = run(limiter, yell);
        assertTrue(peak(out) <= Db.toLinear(-1) + 1e-6, "peak " + Db.fromLinear(peak(out)));
    }

    @Test
    void limiterIsTransparentBelowTheCeiling() {
        MicrophoneSettings s = settings();
        LimiterStage limiter = new LimiterStage(SR);
        limiter.configure(s);
        float[] quiet = sine(400, 0.3, SR / 5);
        float[] out = run(limiter, quiet);
        int delay = limiter.latencySamples();
        for (int i = delay; i < quiet.length; i++) {
            assertEquals(quiet[i - delay], out[i], 1e-6);
        }
    }

    @Test
    void stagesDoNotAllocateOrExplodeOnSilenceForLong() {
        MicrophoneChain chain = new MicrophoneChain(new PassThroughStage());
        MicrophoneSettings s = settings();
        s.deEsser.enabled = true;
        s.agc.enabled = true;
        chain.configure(s);
        float[] block = new float[480];
        for (int i = 0; i < 6000; i++) {                      // one minute of digital silence
            java.util.Arrays.fill(block, 0f);
            chain.process(block, 0, 480);
        }
        for (float v : block) {
            assertFalse(Float.isNaN(v) || Float.isInfinite(v));
        }
    }

    /** Minimal stand-in for the AI slot. */
    static final class PassThroughStage implements AudioStage {
        @Override public String id() { return "ai"; }
        @Override public void configure(MicrophoneSettings settings) { }
        @Override public boolean isEnabled() { return false; }
        @Override public void process(float[] samples, int offset, int length) { }
        @Override public void reset() { }
    }
}
