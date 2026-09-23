package dev.streamable.audio.ai;

/**
 * A stateful, frame-by-frame speech-enhancement network operating on one STFT
 * frame at a time - the streaming contract DPDFNet, DeepFilterNet2 (DPDFNet
 * baseline) and GTCRN all ship ONNX exports for.
 *
 * <p>Implementations keep their recurrent state between calls; that state is
 * what makes per-frame inference equivalent to running the network over the
 * whole signal. They must be used from one thread.</p>
 */
public interface SpectralModel extends AutoCloseable {

    /** Number of frequency bins per frame ({@code fft/2 + 1}). */
    int frequencyBins();

    /**
     * Enhances one frame.
     *
     * @param spectrum interleaved real/imaginary, {@code 2 * frequencyBins()} values
     * @param enhanced receives the enhanced spectrum, same layout
     */
    void process(float[] spectrum, float[] enhanced) throws Exception;

    /** Clears the recurrent state (device change, long gap). */
    void resetState();

    @Override
    void close();
}
