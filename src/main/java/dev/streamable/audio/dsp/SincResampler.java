package dev.streamable.audio.dsp;

/**
 * Streaming band-limited resampler (Kaiser-windowed sinc, table interpolated).
 *
 * <p>Used only at boundaries: 48 kHz to a 16 kHz model and back, and for
 * capture devices that cannot deliver 48 kHz. The anti-aliasing cut-off sits at
 * 90 % of the lower Nyquist frequency with a 32-zero-crossing kernel, giving
 * about 80 dB of stop-band rejection - no audible aliasing or imaging in
 * speech. State carries across calls, so blocks join seamlessly and the sample
 * count is exact over any run length (fractional phase is tracked in
 * {@code long} ticks, not accumulated floating point).</p>
 */
public final class SincResampler {

    private static final int ZERO_CROSSINGS = 32;
    private static final int TABLE_RESOLUTION = 512;
    private static final double KAISER_BETA = 8.0;

    private final int inputRate;
    private final int outputRate;
    private final double cutoff;          // relative to the input Nyquist
    private final int halfWidth;          // taps on each side, in input samples
    private final float[] table;          // kernel(x) for x in [0, halfWidth] at TABLE_RESOLUTION per sample
    private final float[] history;
    private int historyLength;
    /** Position of the next output sample in input samples, as ticks of 1/outputRate. */
    private long positionTicks;

    public SincResampler(int inputRate, int outputRate) {
        if (inputRate <= 0 || outputRate <= 0) {
            throw new IllegalArgumentException("Sample rates must be positive");
        }
        this.inputRate = inputRate;
        this.outputRate = outputRate;
        this.cutoff = 0.90 * Math.min(1.0, outputRate / (double) inputRate);
        this.halfWidth = (int) Math.ceil(ZERO_CROSSINGS / cutoff);
        this.table = new float[halfWidth * TABLE_RESOLUTION + 2];
        double i0Beta = besselI0(KAISER_BETA);
        for (int i = 0; i < table.length; i++) {
            double x = i / (double) TABLE_RESOLUTION;
            if (x > halfWidth) {
                table[i] = 0;
                continue;
            }
            double sinc = x == 0 ? 1 : Math.sin(Math.PI * x * cutoff) / (Math.PI * x * cutoff);
            double ratio = x / halfWidth;
            double window = besselI0(KAISER_BETA * Math.sqrt(Math.max(0, 1 - ratio * ratio))) / i0Beta;
            table[i] = (float) (cutoff * sinc * window);
        }
        this.history = new float[2 * halfWidth + 16384];
        reset();
    }

    public int inputRate() {
        return inputRate;
    }

    public int outputRate() {
        return outputRate;
    }

    /**
     * Buffering delay in output samples: output sample {@code i} is aligned
     * with input time {@code i * in / out}, but can only be produced once
     * {@code halfWidth} later input samples have arrived.
     */
    public double latencyOutputSamples() {
        return halfWidth * (double) outputRate / inputRate;
    }

    public void reset() {
        java.util.Arrays.fill(history, 0f);
        // Prime with halfWidth zeros so the first output sample is centred.
        historyLength = halfWidth;
        positionTicks = 0;
    }

    /** Maximum output samples for {@code inputLength} input samples. */
    public int maxOutput(int inputLength) {
        return (int) ((long) inputLength * outputRate / inputRate) + 2;
    }

    /**
     * Resamples a block.
     *
     * @return number of samples written to {@code output}
     */
    public int process(float[] input, int offset, int length, float[] output) {
        if (historyLength + length > history.length) {
            throw new IllegalArgumentException("Block too large for the resampler");
        }
        System.arraycopy(input, offset, history, historyLength, length);
        historyLength += length;

        int written = 0;
        long ticksPerInput = outputRate;          // one input sample = outputRate ticks
        while (true) {
            long centreIndex = positionTicks / ticksPerInput + halfWidth;
            if (centreIndex + halfWidth >= historyLength) {
                break;
            }
            double fraction = (positionTicks % ticksPerInput) / (double) ticksPerInput;
            double sum = 0;
            int base = (int) centreIndex;
            for (int k = -halfWidth + 1; k <= halfWidth; k++) {
                double distance = Math.abs(k - fraction);
                double tablePosition = distance * TABLE_RESOLUTION;
                int ti = (int) tablePosition;
                if (ti >= table.length - 1) {
                    continue;
                }
                double frac = tablePosition - ti;
                double weight = table[ti] + (table[ti + 1] - table[ti]) * frac;
                sum += history[base + k] * weight;
            }
            output[written++] = (float) sum;
            positionTicks += inputRate;           // one output sample = inputRate ticks
        }
        // Discard input no longer needed, keeping ticks relative to the new start.
        long consumed = positionTicks / ticksPerInput;
        if (consumed > 0) {
            int drop = (int) consumed;
            System.arraycopy(history, drop, history, 0, historyLength - drop);
            historyLength -= drop;
            positionTicks -= consumed * ticksPerInput;
        }
        return written;
    }

    private static double besselI0(double x) {
        double sum = 1;
        double term = 1;
        double half = x / 2;
        for (int k = 1; k < 50; k++) {
            term *= (half / k) * (half / k);
            sum += term;
            if (term < 1e-12 * sum) {
                break;
            }
        }
        return sum;
    }
}
