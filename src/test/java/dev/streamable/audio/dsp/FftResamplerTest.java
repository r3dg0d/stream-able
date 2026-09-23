package dev.streamable.audio.dsp;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FftResamplerTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 8, 12, 15, 320, 512, 960, 7 * 11})
    void matchesNaiveDft(int n) {
        java.util.Random random = new java.util.Random(n);
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) {
            re[i] = random.nextGaussian();
            im[i] = random.nextGaussian();
        }
        double[] expectRe = new double[n];
        double[] expectIm = new double[n];
        for (int k = 0; k < n; k++) {
            for (int t = 0; t < n; t++) {
                double a = -2 * Math.PI * (long) k * t / n;
                expectRe[k] += re[t] * Math.cos(a) - im[t] * Math.sin(a);
                expectIm[k] += re[t] * Math.sin(a) + im[t] * Math.cos(a);
            }
        }
        double[] r = re.clone();
        double[] i = im.clone();
        Fft fft = new Fft(n);
        fft.forward(r, i);
        for (int k = 0; k < n; k++) {
            assertEquals(expectRe[k], r[k], 1e-9 * n, "re " + k);
            assertEquals(expectIm[k], i[k], 1e-9 * n, "im " + k);
        }
        fft.inverse(r, i);
        for (int t = 0; t < n; t++) {
            assertEquals(re[t], r[t], 1e-9);
            assertEquals(im[t], i[t], 1e-9);
        }
    }

    @Test
    void realFftRoundTripsLikeNumpy() {
        RealFft fft = new RealFft(960);
        float[] time = Signals.noise(0.5, 960, 4);
        float[] spectrum = new float[2 * fft.bins()];
        fft.forward(time, spectrum);
        assertEquals(481, fft.bins());
        double dc = 0;
        for (float v : time) {
            dc += v;
        }
        assertEquals(dc, spectrum[0], 1e-3, "bin 0 is the sum (no normalisation), like numpy.rfft");
        float[] back = new float[960];
        fft.inverse(spectrum, back);
        for (int i = 0; i < 960; i++) {
            assertEquals(time[i], back[i], 1e-5);
        }
    }

    private static float[] resampleAll(SincResampler resampler, float[] input, int block) {
        float[] out = new float[resampler.maxOutput(input.length) + 16];
        float[] scratch = new float[resampler.maxOutput(block) + 4];
        int total = 0;
        for (int i = 0; i < input.length; i += block) {
            int n = resampler.process(input, i, Math.min(block, input.length - i), scratch);
            System.arraycopy(scratch, 0, out, total, n);
            total += n;
        }
        return java.util.Arrays.copyOf(out, total);
    }

    @Test
    void downAndUpPreserveSpeechBand() {
        float[] tone = Signals.sine(1000, 0.5, 48_000);
        float[] down = resampleAll(new SincResampler(48_000, 16_000), tone, 480);
        float[] up = resampleAll(new SincResampler(16_000, 48_000), down, 160);
        double inDb = Signals.rmsDb(tone, 10_000, 40_000);
        double outDb = Signals.rmsDb(up, 10_000, 40_000);
        assertEquals(inDb, outDb, 0.05, "1 kHz passes both conversions unchanged in level");
    }

    @Test
    void rejectsContentAboveTheNewNyquist() {
        float[] alias = Signals.sine(11_000, 0.5, 48_000);   // would fold to 5 kHz at 16 kHz
        float[] down = resampleAll(new SincResampler(48_000, 16_000), alias, 480);
        double rejection = Signals.rmsDb(alias, 4_000, 44_000) - Signals.rmsDb(down, 2_000, 14_000);
        assertTrue(rejection > 70, "anti-aliasing rejection " + rejection + " dB");
    }

    @Test
    void sampleCountsAreExactOverLongRuns() {
        SincResampler down = new SincResampler(48_000, 16_000);
        float[] block = new float[480];
        float[] out = new float[down.maxOutput(480) + 4];
        long produced = 0;
        for (int i = 0; i < 100 * 60; i++) {                   // one minute of 10 ms blocks
            produced += down.process(block, 0, 480, out);
        }
        long expected = 16_000L * 60;
        assertTrue(Math.abs(produced - expected) <= 40, "no drift: " + produced + " vs " + expected);

        SincResampler odd = new SincResampler(44_100, 48_000);
        float[] cd = new float[441];
        float[] cdOut = new float[odd.maxOutput(441) + 4];
        long made = 0;
        for (int i = 0; i < 100 * 60; i++) {
            made += odd.process(cd, 0, 441, cdOut);
        }
        assertTrue(Math.abs(made - 48_000L * 60) <= 60, "44.1 -> 48 kHz exact: " + made);
    }
}
