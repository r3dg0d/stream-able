package dev.streamable.audio.ai.ort;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import dev.streamable.audio.ai.InferenceEngine;
import dev.streamable.audio.ai.SpectralModel;

import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ONNX Runtime (CPU) implementation of {@link InferenceEngine}.
 *
 * <p><b>Class loading:</b> this package is compiled against ONNX Runtime but
 * never loaded by the mod's own class loader. {@code IsolatedModelLoader}
 * defines these classes together with the verified, downloaded runtime jar, so
 * a missing or broken runtime can only fail inside that loader.</p>
 *
 * <p>Sessions run single-threaded (one intra-op thread): the models are small
 * enough that thread hand-off costs more than it saves at a 10 ms hop, and it
 * keeps inference from competing with Minecraft for cores.</p>
 */
public final class OrtInferenceEngine implements InferenceEngine {

    private final OrtEnvironment environment;

    public OrtInferenceEngine() {
        this.environment = OrtEnvironment.getEnvironment();
    }

    @Override
    public String version() {
        return environment.getVersion();
    }

    @Override
    public SpectralModel open(Path modelFile, Contract contract, int threads) throws Exception {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(1, threads));
        options.setInterOpNumThreads(1);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        OrtSession session = environment.createSession(modelFile.toString(), options);
        try {
            return switch (contract) {
                case DPDFNET -> new DpdfnetModel(environment, session);
                case GTCRN -> new GtcrnModel(environment, session);
            };
        } catch (Exception e) {
            session.close();
            throw e;
        }
    }

    @Override
    public void close() {
        // The environment is a process-wide singleton owned by ONNX Runtime.
    }

    static long[] shape(NodeInfo info) {
        long[] shape = ((TensorInfo) info.getInfo()).getShape().clone();
        for (int i = 0; i < shape.length; i++) {
            if (shape[i] < 0) {
                shape[i] = 1;
            }
        }
        return shape;
    }

    static int elements(long[] shape) {
        long total = 1;
        for (long dimension : shape) {
            total *= dimension;
        }
        return Math.toIntExact(total);
    }

    /**
     * DPDFNet streaming contract (also the DeepFilterNet2 "baseline"):
     * {@code spec [1,1,F,2]} and a flat {@code state} in, the enhanced spectrum
     * and the next state out. The initial state is built from the ERB and
     * spectral normalisation statistics stored in the model's metadata.
     */
    static final class DpdfnetModel implements SpectralModel {
        private final OrtEnvironment env;
        private final OrtSession session;
        private final String specName;
        private final String stateName;
        private final long[] specShape;
        private final long[] stateShape;
        private final int bins;
        private final float[] initialState;
        private float[] state;
        private final FloatBuffer specBuffer;
        private final FloatBuffer stateBuffer;

        DpdfnetModel(OrtEnvironment env, OrtSession session) throws OrtException {
            this.env = env;
            this.session = session;
            List<String> inputs = new ArrayList<>(session.getInputNames());
            if (inputs.size() < 2 || session.getOutputNames().size() < 2) {
                throw new IllegalArgumentException("Not a streaming DPDFNet model (expected spec + state)");
            }
            Map<String, NodeInfo> info = session.getInputInfo();
            this.specName = inputs.get(0);
            this.stateName = inputs.get(1);
            this.specShape = shape(info.get(specName));
            this.stateShape = shape(info.get(stateName));
            this.bins = (int) specShape[specShape.length - 2];
            Map<String, String> meta = session.getMetadata().getCustomMetadata();
            this.initialState = initialState(meta, elements(stateShape));
            this.state = initialState.clone();
            this.specBuffer = FloatBuffer.allocate(elements(specShape));
            this.stateBuffer = FloatBuffer.allocate(elements(stateShape));
        }

        static float[] initialState(Map<String, String> meta, int size) {
            int declared = Integer.parseInt(require(meta, "state_size"));
            if (declared != size) {
                throw new IllegalArgumentException("State size mismatch: metadata " + declared + ", tensor " + size);
            }
            int erbSize = Integer.parseInt(require(meta, "erb_norm_state_size"));
            int specSize = Integer.parseInt(require(meta, "spec_norm_state_size"));
            float[] state = new float[size];
            fill(state, 0, erbSize, require(meta, "erb_norm_init"));
            fill(state, erbSize, specSize, require(meta, "spec_norm_init"));
            return state;
        }

        private static String require(Map<String, String> meta, String key) {
            String value = meta.get(key);
            if (value == null) {
                throw new IllegalArgumentException("Model metadata is missing '" + key + "'");
            }
            return value;
        }

        private static void fill(float[] target, int offset, int count, String csv) {
            String[] parts = csv.split(",");
            if (parts.length != count) {
                throw new IllegalArgumentException("Expected " + count + " initial values, found " + parts.length);
            }
            for (int i = 0; i < count; i++) {
                target[offset + i] = Float.parseFloat(parts[i].trim());
            }
        }

        @Override
        public int frequencyBins() {
            return bins;
        }

        @Override
        public void process(float[] spectrum, float[] enhanced) throws OrtException {
            specBuffer.clear();
            specBuffer.put(spectrum, 0, 2 * bins).flip();
            stateBuffer.clear();
            stateBuffer.put(state).flip();
            Map<String, OnnxTensor> feed = new HashMap<>(4);
            try (OnnxTensor spec = OnnxTensor.createTensor(env, specBuffer, specShape);
                 OnnxTensor st = OnnxTensor.createTensor(env, stateBuffer, stateShape)) {
                feed.put(specName, spec);
                feed.put(stateName, st);
                try (OrtSession.Result result = session.run(feed)) {
                    FloatBuffer out = ((OnnxTensor) result.get(0)).getFloatBuffer();
                    out.get(enhanced, 0, 2 * bins);
                    FloatBuffer next = ((OnnxTensor) result.get(1)).getFloatBuffer();
                    next.get(state, 0, state.length);
                }
            }
        }

        @Override
        public void resetState() {
            state = initialState.clone();
        }

        @Override
        public void close() {
            try {
                session.close();
            } catch (OrtException e) {
                // Closing a session only releases native memory.
            }
        }
    }

    /**
     * GTCRN streaming contract: {@code mix [1,257,1,2]} plus three caches
     * (convolution, temporal-recurrent attention, inter-frame GRU) that are
     * zero-initialised and threaded from each call to the next.
     */
    static final class GtcrnModel implements SpectralModel {
        private final OrtEnvironment env;
        private final OrtSession session;
        private final List<String> inputNames;
        private final List<long[]> shapes = new ArrayList<>();
        private final List<float[]> caches = new ArrayList<>();
        private final List<FloatBuffer> buffers = new ArrayList<>();
        private final int bins;

        GtcrnModel(OrtEnvironment env, OrtSession session) throws OrtException {
            this.env = env;
            this.session = session;
            this.inputNames = new ArrayList<>(session.getInputNames());
            if (inputNames.size() != 4 || session.getOutputNames().size() != 4) {
                throw new IllegalArgumentException("Not a streaming GTCRN model (expected mix + 3 caches)");
            }
            Map<String, NodeInfo> info = session.getInputInfo();
            for (String name : inputNames) {
                long[] shape = shape(info.get(name));
                shapes.add(shape);
                buffers.add(FloatBuffer.allocate(elements(shape)));
                caches.add(new float[elements(shape)]);
            }
            this.bins = (int) shapes.get(0)[1];
        }

        @Override
        public int frequencyBins() {
            return bins;
        }

        @Override
        public void process(float[] spectrum, float[] enhanced) throws OrtException {
            Map<String, OnnxTensor> feed = new HashMap<>(8);
            List<OnnxTensor> tensors = new ArrayList<>(4);
            try {
                for (int i = 0; i < inputNames.size(); i++) {
                    FloatBuffer buffer = buffers.get(i);
                    buffer.clear();
                    if (i == 0) {
                        // [1, F, 1, 2]: bin-major, real then imaginary - the same interleaving.
                        buffer.put(spectrum, 0, 2 * bins);
                    } else {
                        buffer.put(caches.get(i));
                    }
                    buffer.flip();
                    OnnxTensor tensor = OnnxTensor.createTensor(env, buffer, shapes.get(i));
                    tensors.add(tensor);
                    feed.put(inputNames.get(i), tensor);
                }
                try (OrtSession.Result result = session.run(feed)) {
                    ((OnnxTensor) result.get(0)).getFloatBuffer().get(enhanced, 0, 2 * bins);
                    for (int i = 1; i < 4; i++) {
                        ((OnnxTensor) result.get(i)).getFloatBuffer().get(caches.get(i), 0, caches.get(i).length);
                    }
                }
            } finally {
                for (OnnxTensor tensor : tensors) {
                    tensor.close();
                }
            }
        }

        @Override
        public void resetState() {
            for (int i = 1; i < caches.size(); i++) {
                java.util.Arrays.fill(caches.get(i), 0f);
            }
        }

        @Override
        public void close() {
            try {
                session.close();
            } catch (OrtException e) {
                // Closing a session only releases native memory.
            }
        }
    }
}
