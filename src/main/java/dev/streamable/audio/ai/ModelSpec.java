package dev.streamable.audio.ai;

import dev.streamable.config.MicrophoneSettings;

/**
 * Everything the streaming wrapper needs to know about one model.
 *
 * @param backend           which choice in the UI this is
 * @param runtimeId         manifest component holding the model file
 * @param fileName          file inside that component
 * @param contract          ONNX tensor contract
 * @param sampleRate        rate the model runs at
 * @param fftSize           analysis window length (samples at {@code sampleRate})
 * @param hopSize           hop length
 * @param window            analysis/synthesis window
 * @param modelDelaySamples how far the enhanced output lags the input in sample
 *                          index (the network's look-ahead), at {@code sampleRate};
 *                          measured by cross-correlation in ModelIntegrationTest
 *                          and needed to blend dry and enhanced audio exactly
 * @param name              human-readable name and version for diagnostics
 */
public record ModelSpec(MicrophoneSettings.NoiseBackend backend, String runtimeId, String fileName,
                        InferenceEngine.Contract contract, int sampleRate, int fftSize, int hopSize,
                        StftWindow window, int modelDelaySamples, String name) {

    public enum StftWindow { VORBIS, SQRT_HANN }

    /** DPDFNet-2, native 48 kHz: 20 ms window, 10 ms hop. The default. */
    public static final ModelSpec DPDFNET_48K = new ModelSpec(MicrophoneSettings.NoiseBackend.DPDFNET,
            "model-dpdfnet2-48k", "dpdfnet2_48khz_hr.onnx", InferenceEngine.Contract.DPDFNET,
            48_000, 960, 480, StftWindow.VORBIS, 1920, "DPDFNet-2 48 kHz HR (Ceva, 2025)");

    /** The DeepFilterNet2 architecture (DPDFNet "baseline", zero DPRNN blocks), 16 kHz. */
    public static final ModelSpec DFN2_16K = new ModelSpec(MicrophoneSettings.NoiseBackend.DEEPFILTERNET,
            "model-dfn2-16k", "dfn2_baseline_16khz.onnx", InferenceEngine.Contract.DPDFNET,
            16_000, 320, 160, StftWindow.VORBIS, 640, "DeepFilterNet2 16 kHz (DPDFNet baseline checkpoint)");

    /** GTCRN streaming model, 16 kHz, 32 ms window, 16 ms hop. Tiny CPU cost. */
    public static final ModelSpec GTCRN_16K = new ModelSpec(MicrophoneSettings.NoiseBackend.GTCRN,
            "model-gtcrn-16k", "gtcrn_simple.onnx", InferenceEngine.Contract.GTCRN,
            16_000, 512, 256, StftWindow.SQRT_HANN, 0, "GTCRN 16 kHz stream (ICASSP 2024, DNS3 weights)");

    public static ModelSpec forBackend(MicrophoneSettings.NoiseBackend backend) {
        return switch (backend) {
            case DPDFNET, AUTO -> DPDFNET_48K;
            case DEEPFILTERNET -> DFN2_16K;
            case GTCRN -> GTCRN_16K;
        };
    }

    public double hopMillis() {
        return hopSize * 1000.0 / sampleRate;
    }

    /** Synthesis/analysis windows satisfying w[n]^2 + w[n+hop]^2 = 1 at 50 % overlap. */
    public float[] makeWindow() {
        float[] w = new float[fftSize];
        for (int i = 0; i < fftSize; i++) {
            if (window == StftWindow.VORBIS) {
                double half = fftSize / 2.0;
                double s = Math.sin(0.5 * Math.PI * (i + 0.5) / half);
                w[i] = (float) Math.sin(0.5 * Math.PI * s * s);
            } else {
                // Periodic Hann, square-rooted (torch.hann_window(n).pow(0.5)).
                w[i] = (float) Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / fftSize));
            }
        }
        return w;
    }
}
