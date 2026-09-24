package dev.streamable.audio.dsp;

import dev.streamable.config.MicrophoneSettings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-stage cost of the microphone chain on this machine, in the real block
 * size (480 samples = 10 ms at 48 kHz). Opt-in (-Dstreamable.benchmarks=true)
 * because timings depend on the machine and must not fail ordinary builds;
 * the only assertion is a generous real-time bound.
 */
class DspBenchmarkTest {

    private static final int BLOCK = 480;
    private static final int WARMUP_BLOCKS = 3_000;      // 30 s of audio, lets the JIT settle
    private static final int MEASURED_BLOCKS = 12_000;   // 2 minutes of audio

    @Test
    void chainCostPerBlock() {
        Assumptions.assumeTrue(Boolean.getBoolean("streamable.benchmarks"), "benchmarks are opt-in");

        MicrophoneSettings settings = new MicrophoneSettings();
        settings.deEsser.enabled = true;
        settings.agc.enabled = true;
        settings.validate();
        MicrophoneChain chain = new MicrophoneChain(new DspStagesTest.PassThroughStage());
        chain.configure(settings);

        // Speech-like test signal: a swept tone with noise, at a normal level.
        float[] signal = new float[BLOCK * 200];
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < signal.length; i++) {
            double t = i / (double) MicrophoneChain.SAMPLE_RATE;
            signal[i] = (float) (0.2 * Math.sin(2 * Math.PI * (150 + 400 * Math.sin(t)) * t) + 0.01 * random.nextGaussian());
        }

        Map<String, long[]> perStage = new LinkedHashMap<>();
        for (AudioStage stage : chain.stages()) {
            perStage.put(stage.id(), new long[1]);
        }
        float[] block = new float[BLOCK];
        long chainNanos = 0;
        for (int b = 0; b < WARMUP_BLOCKS + MEASURED_BLOCKS; b++) {
            System.arraycopy(signal, (b % 200) * BLOCK, block, 0, BLOCK);
            boolean measure = b >= WARMUP_BLOCKS;
            long start = System.nanoTime();
            for (AudioStage stage : chain.stages()) {
                if (!stage.isEnabled()) {
                    continue;
                }
                long s = System.nanoTime();
                stage.process(block, 0, BLOCK);
                if (measure) {
                    perStage.get(stage.id())[0] += System.nanoTime() - s;
                }
            }
            if (measure) {
                chainNanos += System.nanoTime() - start;
            }
        }

        StringBuilder report = new StringBuilder("Microphone chain, 480-sample blocks (10 ms), averaged over "
                + MEASURED_BLOCKS + " blocks:\n");
        for (Map.Entry<String, long[]> e : perStage.entrySet()) {
            double micros = e.getValue()[0] / 1000.0 / MEASURED_BLOCKS;
            report.append(String.format(Locale.ROOT, "  %-12s %7.2f us/block%n", e.getKey(), micros));
        }
        double chainMicros = chainNanos / 1000.0 / MEASURED_BLOCKS;
        report.append(String.format(Locale.ROOT, "  %-12s %7.2f us/block = %.2f%% of real time (AI stage excluded)%n",
                "whole chain", chainMicros, chainMicros / 10_000.0 * 100));
        System.out.println(report);
        assertTrue(chainMicros < 5_000, "the DSP chain must use well under half of real time");
    }
}
