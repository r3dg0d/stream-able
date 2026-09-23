package dev.streamable.audio.ai;

import java.nio.file.Path;

/**
 * Loads models into a portable inference runtime.
 *
 * <p>This interface lives in the mod's own class loader; the ONNX Runtime
 * implementation ({@code dev.streamable.audio.ai.ort.OrtInferenceEngine}) is
 * defined inside an isolated loader together with the downloaded runtime jar,
 * so the mod never links against ONNX Runtime directly.</p>
 */
public interface InferenceEngine extends AutoCloseable {

    /** Which input/output contract a model file follows. */
    enum Contract {
        /** {@code (spec[1,1,F,2], state[S]) -> (spec_e, state_out)}; initial state from metadata. */
        DPDFNET,
        /** {@code (mix[1,F,1,2], conv_cache, tra_cache, inter_cache) -> (enh, caches...)}. */
        GTCRN
    }

    SpectralModel open(Path modelFile, Contract contract, int threads) throws Exception;

    String version();

    @Override
    void close();
}
