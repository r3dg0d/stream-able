package dev.streamable.audio.dsp;

/**
 * Mixed-radix complex FFT for any size whose prime factors are small
 * (2, 3, 5, and generic radices for anything else).
 *
 * <p>The speech models use 320-, 512- and 960-point transforms; 960 = 2^6*3*5
 * is not a power of two, so a radix-2 FFT would not do. The plan (factors and
 * twiddles) is computed once; transforms allocate nothing.</p>
 */
public final class Fft {

    private final int n;
    private final int[] factors;
    private final double[] cos;
    private final double[] sin;
    private final double[] scratchRe;
    private final double[] scratchIm;
    private final double[] butterflyRe;
    private final double[] butterflyIm;

    public Fft(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("FFT size must be positive");
        }
        this.n = n;
        this.factors = factorise(n);
        this.cos = new double[n];
        this.sin = new double[n];
        for (int i = 0; i < n; i++) {
            double angle = -2 * Math.PI * i / n;
            cos[i] = Math.cos(angle);
            sin[i] = Math.sin(angle);
        }
        this.scratchRe = new double[n];
        this.scratchIm = new double[n];
        int maxRadix = 1;
        for (int f : factors) {
            maxRadix = Math.max(maxRadix, f);
        }
        this.butterflyRe = new double[maxRadix];
        this.butterflyIm = new double[maxRadix];
    }

    public int size() {
        return n;
    }

    static int[] factorise(int n) {
        java.util.List<Integer> list = new java.util.ArrayList<>();
        int remaining = n;
        for (int radix : new int[]{4, 2, 3, 5}) {
            while (remaining % radix == 0) {
                list.add(radix);
                remaining /= radix;
            }
        }
        for (int p = 7; remaining > 1; p += 2) {
            while (remaining % p == 0) {
                list.add(p);
                remaining /= p;
            }
        }
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    /** In-place forward transform (no normalisation). */
    public void forward(double[] re, double[] im) {
        transform(re, im, false);
    }

    /** In-place inverse transform, normalised by 1/n. */
    public void inverse(double[] re, double[] im) {
        transform(re, im, true);
        double scale = 1.0 / n;
        for (int i = 0; i < n; i++) {
            re[i] *= scale;
            im[i] *= scale;
        }
    }

    private void transform(double[] re, double[] im, boolean inverse) {
        System.arraycopy(re, 0, scratchRe, 0, n);
        System.arraycopy(im, 0, scratchIm, 0, n);
        recurse(scratchRe, scratchIm, 0, 1, re, im, 0, n, 0, inverse);
    }

    /**
     * Decimation in time: transforms the {@code count} elements of the input
     * starting at {@code inOffset} with stride {@code stride} into
     * {@code out[outOffset..outOffset+count)}.
     */
    private void recurse(double[] inRe, double[] inIm, int inOffset, int stride,
                         double[] outRe, double[] outIm, int outOffset, int count, int factorIndex,
                         boolean inverse) {
        if (count == 1) {
            outRe[outOffset] = inRe[inOffset];
            outIm[outOffset] = inIm[inOffset];
            return;
        }
        int radix = factors[factorIndex];
        int m = count / radix;
        for (int q = 0; q < radix; q++) {
            recurse(inRe, inIm, inOffset + q * stride, stride * radix, outRe, outIm, outOffset + q * m, m,
                    factorIndex + 1, inverse);
        }
        int twiddleStep = n / count;
        double sign = inverse ? -1 : 1;
        for (int k = 0; k < m; k++) {
            // Twiddled inputs of this butterfly.
            for (int q = 0; q < radix; q++) {
                int index = outOffset + q * m + k;
                int t = (q * k * twiddleStep) % n;
                double wr = cos[t];
                double wi = sign * sin[t];
                double xr = outRe[index];
                double xi = outIm[index];
                butterflyRe[q] = xr * wr - xi * wi;
                butterflyIm[q] = xr * wi + xi * wr;
            }
            // Radix-r DFT of the twiddled values.
            for (int j = 0; j < radix; j++) {
                double sumRe = 0;
                double sumIm = 0;
                for (int q = 0; q < radix; q++) {
                    // W_radix^(q*j) = W_n^((q*j mod radix) * n/radix)
                    int t = (int) (((long) q * j % radix) * (n / radix) % n);
                    double wr = cos[t];
                    double wi = sign * sin[t];
                    sumRe += butterflyRe[q] * wr - butterflyIm[q] * wi;
                    sumIm += butterflyRe[q] * wi + butterflyIm[q] * wr;
                }
                outRe[outOffset + j * m + k] = sumRe;
                outIm[outOffset + j * m + k] = sumIm;
            }
        }
    }
}
