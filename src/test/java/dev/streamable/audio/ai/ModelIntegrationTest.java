package dev.streamable.audio.ai;

import dev.streamable.audio.ai.ort.OrtInferenceEngine;
import dev.streamable.audio.dsp.SincResampler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real ONNX inference against the pinned model files. Needs the models on disk
 * (set STREAMABLE_MODEL_DIR); skipped in CI where they are not downloaded.
 * Prints measured latency, real-time factor and noise reduction.
 */
class ModelIntegrationTest {

    static Path dir;

    @BeforeAll
    static void locate() {
        String env = System.getenv("STREAMABLE_MODEL_DIR");
        Assumptions.assumeTrue(env != null && Files.isDirectory(Path.of(env)), "STREAMABLE_MODEL_DIR not set");
        dir = Path.of(env);
    }

    static float[] readWav(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            String id = new String(bytes, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = buffer.getInt(pos + 4);
            if (id.equals("data")) {
                float[] out = new float[size / 2];
                for (int i = 0; i < out.length; i++) {
                    out[i] = buffer.getShort(pos + 8 + 2 * i) / 32768f;
                }
                return out;
            }
            pos += 8 + size + (size & 1);
        }
        throw new IOException("no data chunk");
    }

    static float[] enhance(ModelSpec spec, float[] input) throws Exception {
        try (OrtInferenceEngine engine = new OrtInferenceEngine();
             SpectralModel model = engine.open(dir.resolve(spec.fileName()), spec.contract(), 1)) {
            StreamingEnhancer enhancer = new StreamingEnhancer(spec, model);
            List<Float> out = new ArrayList<>(input.length);
            int block = spec.hopSize();
            long start = System.nanoTime();
            for (int i = 0; i + block <= input.length; i += block) {
                enhancer.push(input, i, block, (samples, length) -> {
                    for (int k = 0; k < length; k++) {
                        out.add(samples[k]);
                    }
                });
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            double audioSeconds = input.length / (double) spec.sampleRate();
            System.out.printf("%s: %d frames, avg inference %.3f ms/hop (%.1f ms hop), RTF %.3f (incl. STFT)%n",
                    spec.name(), enhancer.frames(), enhancer.averageInferenceMillis(), spec.hopMillis(),
                    seconds / audioSeconds);
            float[] result = new float[out.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = out.get(i);
            }
            return result;
        }
    }

    /** Lag maximising normalised correlation of b against a, and that correlation. */
    static double[] bestLag(float[] a, float[] b, int maxLag) {
        double best = -2;
        int bestLag = 0;
        int n = Math.min(a.length, b.length) - maxLag;
        for (int lag = 0; lag <= maxLag; lag++) {
            double sab = 0, saa = 0, sbb = 0;
            for (int i = 0; i < n; i++) {
                double x = a[i];
                double y = b[i + lag];
                sab += x * y;
                saa += x * x;
                sbb += y * y;
            }
            double c = sab / Math.sqrt(saa * sbb + 1e-20);
            if (c > best) {
                best = c;
                bestLag = lag;
            }
        }
        return new double[]{bestLag, best};
    }

    static double energyDb(float[] data, int from, int to) {
        double sum = 0;
        for (int i = Math.max(0, from); i < Math.min(data.length, to); i++) {
            sum += data[i] * (double) data[i];
        }
        return 10 * Math.log10(sum / Math.max(1, to - from) + 1e-20);
    }

    @Test
    void gtcrnMatchesTheUpstreamStreamingReference() throws Exception {
        Path mix = dir.resolve("gtcrn_mix.wav");
        Path reference = dir.resolve("gtcrn_enh_stream.wav");
        Assumptions.assumeTrue(Files.exists(mix) && Files.exists(reference));
        float[] input = readWav(mix);
        float[] ours = enhance(ModelSpec.GTCRN_16K, input);
        float[] theirs = readWav(reference);
        double[] lag = bestLag(theirs, ours, 1024);
        System.out.printf("GTCRN vs upstream enh_stream.wav: lag %d samples, correlation %.5f%n", (int) lag[0], lag[1]);
        assertTrue(lag[1] > 0.99, "our streaming pipeline must reproduce the reference: " + lag[1]);
    }

    @Test
    void dpdfnetModelsReduceNoiseAndReportTheirDelay() throws Exception {
        float[] mix16 = readWav(dir.resolve("gtcrn_mix.wav"));
        // Upsample the 16 kHz test clip for the 48 kHz model.
        SincResampler up = new SincResampler(16_000, 48_000);
        float[] tmp = new float[up.maxOutput(mix16.length) + 16];
        float[] block = new float[up.maxOutput(160) + 4];
        int n = 0;
        for (int i = 0; i < mix16.length; i += 160) {
            int produced = up.process(mix16, i, Math.min(160, mix16.length - i), block);
            System.arraycopy(block, 0, tmp, n, produced);
            n += produced;
        }
        float[] mix48 = java.util.Arrays.copyOf(tmp, n);

        for (ModelSpec spec : List.of(ModelSpec.DPDFNET_48K, ModelSpec.DFN2_16K, ModelSpec.GTCRN_16K)) {
            float[] input = spec.sampleRate() == 48_000 ? mix48 : mix16;
            float[] out = enhance(spec, input);
            double[] lag = bestLag(input, out, spec.sampleRate() / 10);
            System.out.printf("%s: index delay %d samples (%.1f ms; spec says %d); corr %.3f; "
                            + "level in %.1f dB -> out %.1f dB%n", spec.name(), (int) lag[0],
                    lag[0] * 1000.0 / spec.sampleRate(), spec.modelDelaySamples(), lag[1],
                    energyDb(input, 0, input.length), energyDb(out, 0, out.length));
            assertTrue(energyDb(out, 0, out.length) < energyDb(input, 0, input.length), "noise removed");
            org.junit.jupiter.api.Assertions.assertEquals(spec.modelDelaySamples(), (int) lag[0],
                    "the delay used for dry/wet alignment must match the measured one");
        }
    }
}
