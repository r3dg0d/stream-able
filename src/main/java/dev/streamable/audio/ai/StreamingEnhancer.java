package dev.streamable.audio.ai;

import dev.streamable.audio.dsp.RealFft;

/**
 * Causal STFT -> model -> overlap-add, one hop at a time, at the model's rate.
 *
 * <p>This is the streaming procedure the model authors ship (DPDFNet's
 * {@code StreamEnhancer}, GTCRN's stream demo): a window of {@code fftSize}
 * samples advances by {@code hopSize}; each frame is windowed, transformed,
 * enhanced by the stateful network and synthesised back with the same window;
 * because the window satisfies the power-complementary condition at 50 %
 * overlap, the first hop of the overlap-add buffer is final after each frame.
 * No multi-second chunks - one 10-16 ms hop per call.</p>
 *
 * <p>Output position {@code p} (counting committed samples from 0) is aligned
 * with input position {@code p} plus the model's intrinsic delay.</p>
 */
public final class StreamingEnhancer {

    /** Receives committed output samples. */
    @FunctionalInterface
    public interface Output {
        void accept(float[] samples, int length);
    }

    private final ModelSpec spec;
    private final SpectralModel model;
    private final RealFft fft;
    private final float[] window;
    private final float[] input;          // sliding analysis buffer, fftSize long
    private int filled;
    private final float[] frame;
    private final float[] spectrum;
    private final float[] enhanced;
    private final float[] synthesis;
    private final float[] overlap;
    private final float[] committed;
    private long frames;
    private double inferenceNanosTotal;
    private volatile double lastInferenceMillis;

    public StreamingEnhancer(ModelSpec spec, SpectralModel model) {
        if (spec.fftSize() != 2 * spec.hopSize()) {
            throw new IllegalArgumentException("Only 50 % overlap models are supported");
        }
        if (model.frequencyBins() != spec.fftSize() / 2 + 1) {
            throw new IllegalArgumentException("Model expects " + model.frequencyBins()
                    + " bins but the spec has an FFT of " + spec.fftSize());
        }
        this.spec = spec;
        this.model = model;
        this.fft = new RealFft(spec.fftSize());
        this.window = spec.makeWindow();
        this.input = new float[spec.fftSize()];
        this.frame = new float[spec.fftSize()];
        this.spectrum = new float[2 * fft.bins()];
        this.enhanced = new float[2 * fft.bins()];
        this.synthesis = new float[spec.fftSize()];
        this.overlap = new float[spec.fftSize()];
        this.committed = new float[spec.hopSize()];
    }

    public ModelSpec spec() {
        return spec;
    }

    /** Pushes model-rate samples; emits each completed hop. */
    public void push(float[] samples, int offset, int length, Output output) throws Exception {
        int hop = spec.hopSize();
        int size = spec.fftSize();
        int i = offset;
        int end = offset + length;
        while (i < end) {
            int take = Math.min(size - filled, end - i);
            System.arraycopy(samples, i, input, filled, take);
            filled += take;
            i += take;
            if (filled == size) {
                processFrame(output);
                System.arraycopy(input, hop, input, 0, size - hop);
                filled = size - hop;
            }
        }
    }

    private void processFrame(Output output) throws Exception {
        int size = spec.fftSize();
        int hop = spec.hopSize();
        for (int n = 0; n < size; n++) {
            frame[n] = input[n] * window[n];
        }
        fft.forward(frame, spectrum);
        long start = System.nanoTime();
        model.process(spectrum, enhanced);
        long elapsed = System.nanoTime() - start;
        inferenceNanosTotal += elapsed;
        lastInferenceMillis = elapsed / 1e6;
        frames++;
        fft.inverse(enhanced, synthesis);
        for (int n = 0; n < size; n++) {
            overlap[n] += synthesis[n] * window[n];
        }
        System.arraycopy(overlap, 0, committed, 0, hop);
        System.arraycopy(overlap, hop, overlap, 0, size - hop);
        java.util.Arrays.fill(overlap, size - hop, size, 0f);
        output.accept(committed, hop);
    }

    public void reset() {
        java.util.Arrays.fill(input, 0f);
        java.util.Arrays.fill(overlap, 0f);
        filled = 0;
        model.resetState();
    }

    /** Framing delay: output position p is final once input up to p + (fft - 1) arrived. */
    public int framingDelaySamples() {
        return spec.fftSize() - spec.hopSize();
    }

    public long frames() {
        return frames;
    }

    public double averageInferenceMillis() {
        return frames == 0 ? 0 : inferenceNanosTotal / frames / 1e6;
    }

    public double lastInferenceMillis() {
        return lastInferenceMillis;
    }
}
