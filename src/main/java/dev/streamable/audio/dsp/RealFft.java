package dev.streamable.audio.dsp;

/**
 * Real-input FFT with the half-spectrum layout NumPy's {@code rfft}/{@code irfft}
 * use ({@code n/2+1} bins), which is what the speech-enhancement models were
 * trained against.
 */
public final class RealFft {

    private final int n;
    private final Fft fft;
    private final double[] re;
    private final double[] im;

    public RealFft(int n) {
        this.n = n;
        this.fft = new Fft(n);
        this.re = new double[n];
        this.im = new double[n];
    }

    public int size() {
        return n;
    }

    public int bins() {
        return n / 2 + 1;
    }

    /** {@code interleaved[2k], [2k+1]} = real and imaginary part of bin {@code k}. */
    public void forward(float[] time, float[] interleaved) {
        for (int i = 0; i < n; i++) {
            re[i] = time[i];
            im[i] = 0;
        }
        fft.forward(re, im);
        for (int k = 0; k < bins(); k++) {
            interleaved[2 * k] = (float) re[k];
            interleaved[2 * k + 1] = (float) im[k];
        }
    }

    /** Inverse of {@link #forward}, normalised like NumPy's {@code irfft}. */
    public void inverse(float[] interleaved, float[] time) {
        int bins = bins();
        for (int k = 0; k < bins; k++) {
            re[k] = interleaved[2 * k];
            im[k] = interleaved[2 * k + 1];
        }
        // Hermitian symmetry for the upper half; DC and Nyquist imaginary parts ignored.
        im[0] = 0;
        if (n % 2 == 0) {
            im[n / 2] = 0;
        }
        for (int k = bins; k < n; k++) {
            re[k] = re[n - k];
            im[k] = -im[n - k];
        }
        fft.inverse(re, im);
        for (int i = 0; i < n; i++) {
            time[i] = (float) re[i];
        }
    }
}
