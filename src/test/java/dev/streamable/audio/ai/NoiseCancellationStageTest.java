package dev.streamable.audio.ai;

import dev.streamable.config.MicrophoneSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The streaming contract with mock models (real inference is covered by
 * ModelIntegrationTest when the model files are available).
 */
class NoiseCancellationStageTest {

    /** Returns its input spectrum: with a power-complementary window the enhancer is then an identity. */
    static final class IdentityModel implements SpectralModel {
        final int bins;
        volatile boolean fail;
        volatile long sleepMillis;
        int calls;

        IdentityModel(int bins) {
            this.bins = bins;
        }

        @Override public int frequencyBins() { return bins; }

        @Override
        public void process(float[] spectrum, float[] enhanced) throws Exception {
            calls++;
            if (fail) {
                throw new IllegalStateException("model crashed");
            }
            if (sleepMillis > 0) {
                Thread.sleep(sleepMillis);
            }
            System.arraycopy(spectrum, 0, enhanced, 0, 2 * bins);
        }

        @Override public void resetState() { }
        @Override public void close() { }
    }

    /** Scales every bin: a stand-in for "the model removed noise". */
    static final class AttenuatingModel implements SpectralModel {
        final int bins;
        final float gain;

        AttenuatingModel(int bins, float gain) {
            this.bins = bins;
            this.gain = gain;
        }

        @Override public int frequencyBins() { return bins; }

        @Override
        public void process(float[] spectrum, float[] enhanced) {
            for (int i = 0; i < 2 * bins; i++) {
                enhanced[i] = spectrum[i] * gain;
            }
        }

        @Override public void resetState() { }
        @Override public void close() { }
    }

    private static ModelSpec withoutDelay(ModelSpec spec) {
        return new ModelSpec(spec.backend(), spec.runtimeId(), spec.fileName(), spec.contract(), spec.sampleRate(),
                spec.fftSize(), spec.hopSize(), spec.window(), 0, spec.name());
    }

    private static MicrophoneSettings settings(MicrophoneSettings.NoiseLevel level) {
        MicrophoneSettings s = new MicrophoneSettings();
        s.noise.level = level;
        s.validate();
        return s;
    }

    private static NoiseCancellationStage stage(ModelSpec spec, SpectralModel model, MicrophoneSettings s) {
        NoiseCancellationStage stage = new NoiseCancellationStage();
        stage.install(new NoiseCancellationStage.Backend(spec, model, "test"));
        stage.configure(s);
        return stage;
    }

    private static float[] tone(int samples) {
        float[] out = new float[samples];
        for (int i = 0; i < samples; i++) {
            out[i] = (float) (0.3 * Math.sin(2 * Math.PI * 440 * i / 48_000.0)
                    + 0.1 * Math.sin(2 * Math.PI * 1700 * i / 48_000.0));
        }
        return out;
    }

    private static float[] run(NoiseCancellationStage stage, float[] input) {
        float[] data = input.clone();
        for (int i = 0; i < data.length; i += 480) {
            stage.process(data, i, 480);
        }
        return data;
    }

    @Test
    void identityModelAt48kIsAnExactDelay() {
        ModelSpec spec = withoutDelay(ModelSpec.DPDFNET_48K);
        MicrophoneSettings s = settings(MicrophoneSettings.NoiseLevel.STRONG);
        NoiseCancellationStage stage = stage(spec, new IdentityModel(481), s);
        float[] in = tone(48_000);
        float[] out = run(stage, in);
        int latency = stage.latencySamples();
        assertEquals(480, latency, "10 ms of STFT framing, nothing more");
        for (int i = latency + 4800; i < in.length; i++) {
            assertEquals(in[i - latency], out[i], 2e-4, "sample " + i);
        }
        assertEquals(0, stage.lateSamples(), "enhanced output is always ready in steady state");
    }

    @Test
    void sixteenKilohertzModelsStayAlignedThroughResampling() {
        ModelSpec spec = withoutDelay(ModelSpec.GTCRN_16K);
        NoiseCancellationStage stage = stage(spec, new IdentityModel(257), settings(MicrophoneSettings.NoiseLevel.STRONG));
        float[] in = tone(96_000);
        float[] out = run(stage, in);
        int latency = stage.latencySamples();
        double error = 0;
        double energy = 0;
        for (int i = latency + 9600; i < in.length; i++) {
            double d = in[i - latency] - out[i];
            error += d * d;
            energy += in[i - latency] * (double) in[i - latency];
        }
        double snr = 10 * Math.log10(energy / error);
        assertTrue(snr > 40, "440/1700 Hz content survives 48k->16k->48k aligned: SNR " + snr + " dB");
        assertEquals(0, stage.lateSamples());
        assertTrue(stage.latencyMillis() < 45, "latency " + stage.latencyMillis() + " ms");
    }

    @Test
    void strengthLevelsBoundTheReduction() {
        ModelSpec spec = withoutDelay(ModelSpec.DPDFNET_48K);
        float[] in = tone(48_000);
        double light = measureReductionDb(stage(spec, new AttenuatingModel(481, 0f), settings(MicrophoneSettings.NoiseLevel.LIGHT)), in);
        double strong = measureReductionDb(stage(spec, new AttenuatingModel(481, 0f), settings(MicrophoneSettings.NoiseLevel.STRONG)), in);
        // A model that removes everything is limited by the attenuation limit
        // (voice preservation adds a little dry back on top).
        assertTrue(light <= 12.5 && light > 5, "Light caps suppression near 12 dB: " + light);
        assertTrue(strong > light + 6, "Strong suppresses much more: " + strong);
    }

    private static double measureReductionDb(NoiseCancellationStage stage, float[] in) {
        float[] out = run(stage, in);
        double a = 0;
        double b = 0;
        for (int i = 24_000; i < in.length; i++) {
            a += in[i] * (double) in[i];
            b += out[i] * (double) out[i];
        }
        return 10 * Math.log10(a / b);
    }

    @Test
    void modelFailureNeverSilencesTheMicrophone() {
        ModelSpec spec = withoutDelay(ModelSpec.DPDFNET_48K);
        IdentityModel model = new IdentityModel(481);
        NoiseCancellationStage stage = stage(spec, model, settings(MicrophoneSettings.NoiseLevel.BALANCED));
        run(stage, tone(24_000));
        model.fail = true;
        float[] out = run(stage, tone(48_000));
        double rms = 0;
        for (int i = 24_000; i < out.length; i++) {
            rms += out[i] * (double) out[i];
        }
        assertTrue(Math.sqrt(rms / 24_000) > 0.1, "the dry voice continues");
        assertTrue(stage.inferenceFailures() > 0);
    }

    @Test
    void overloadKeepsLatencyConstantAndSkipsInference() {
        ModelSpec spec = withoutDelay(ModelSpec.DPDFNET_48K);
        IdentityModel model = new IdentityModel(481);
        NoiseCancellationStage stage = stage(spec, model, settings(MicrophoneSettings.NoiseLevel.BALANCED));
        int latency = stage.latencySamples();
        run(stage, tone(24_000));
        int callsBefore = model.calls;
        stage.setOverloaded(true);
        run(stage, tone(48_000));
        assertEquals(callsBefore, model.calls, "no inference while overloaded");
        assertEquals(latency, stage.latencySamples(), "latency never grows");
        stage.setOverloaded(false);
        run(stage, tone(24_000));
        assertTrue(model.calls > callsBefore, "resumes afterwards");
    }

    @Test
    void bypassIsClickFreeAndLatencyStable() {
        ModelSpec spec = withoutDelay(ModelSpec.DPDFNET_48K);
        MicrophoneSettings s = settings(MicrophoneSettings.NoiseLevel.BALANCED);
        NoiseCancellationStage stage = stage(spec, new AttenuatingModel(481, 0.5f), s);
        float[] in = tone(96_000);
        float[] out = in.clone();
        for (int i = 0; i < out.length; i += 480) {
            if (i == 48_000) {
                s.noise.bypass = true;
                stage.configure(s);
            }
            stage.process(out, i, 480);
        }
        double maxStep = 0;
        for (int i = 47_000; i < 50_000; i++) {
            maxStep = Math.max(maxStep, Math.abs(out[i] - out[i - 1]));
        }
        assertTrue(maxStep < 0.08, "no discontinuity when toggling: " + maxStep);
    }

    @Test
    void swappingBackendsWhileRunningIsSafe() {
        MicrophoneSettings s = settings(MicrophoneSettings.NoiseLevel.BALANCED);
        NoiseCancellationStage stage = stage(withoutDelay(ModelSpec.DPDFNET_48K), new IdentityModel(481), s);
        run(stage, tone(9600));
        stage.install(new NoiseCancellationStage.Backend(withoutDelay(ModelSpec.GTCRN_16K), new IdentityModel(257), "t"));
        run(stage, tone(9600));
        assertEquals(ModelSpec.GTCRN_16K.name(), stage.activeName());
        stage.uninstall();
        run(stage, tone(960));
        assertTrue(!stage.isEnabled());
        stage.closeRetired();
    }
}
