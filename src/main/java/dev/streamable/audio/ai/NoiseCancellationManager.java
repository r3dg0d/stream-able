package dev.streamable.audio.ai;

import dev.streamable.StreamAbleLog;
import dev.streamable.config.MicrophoneSettings;
import dev.streamable.runtime.RuntimeManager;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Chooses, downloads, loads and supervises the noise-cancellation backend.
 *
 * <h2>Fallback</h2>
 * <pre>
 *   preferred backend fails / too slow
 *     -&gt; next compatible backend (DPDFNet -&gt; DeepFilterNet2 -&gt; GTCRN)
 *       -&gt; conventional processing only (gate, EQ, compressor still run)
 * </pre>
 * <p>A backend "fails" when its runtime or model cannot be installed, the model
 * does not load, or a warm-up benchmark shows it cannot run in real time on
 * this CPU. After it is running, the DSP worker reports sustained overruns and
 * the manager moves to a lighter model. The microphone keeps working through
 * every step - the stage passes the dry voice while nothing is loaded.</p>
 *
 * <p>All loading happens on a dedicated background thread, never on the render
 * or DSP thread. All inference is local; microphone audio is never uploaded.</p>
 */
public final class NoiseCancellationManager implements AutoCloseable {

    /** What the UI shows about AI noise cancellation. */
    public record Status(State state, String detail, String activeModel, String engineVersion,
                         double latencyMillis, double inferenceMillis, double realTimeFactor,
                         List<String> fallbacks) {
        public enum State { OFF, LOADING, ACTIVE, DEGRADED, UNAVAILABLE }

        static Status off() {
            return new Status(State.OFF, "AI noise cancellation is off.", "", "", 0, 0, 0, List.of());
        }
    }

    /** Warm-up inference must stay below this share of the hop to be accepted. */
    static final double MAX_WARMUP_RTF = 0.5;
    /** Sustained real-time factor above which a running backend is demoted. */
    static final double DEMOTE_RTF = 0.85;

    /** Turns a backend choice into a loaded, benchmarked model. */
    @FunctionalInterface
    public interface BackendFactory {
        /**
         * @param progress receives human-readable progress lines
         * @return the loaded backend; throws with a user-facing reason on failure
         */
        NoiseCancellationStage.Backend prepare(MicrophoneSettings.NoiseBackend backend,
                                               java.util.function.Consumer<String> progress) throws Exception;
    }

    private final AudioInferenceRuntime onnx;
    private final Map<MicrophoneSettings.NoiseBackend, ModelRuntime> models = new EnumMap<>(MicrophoneSettings.NoiseBackend.class);
    private final NoiseCancellationStage stage;
    private final BackendFactory factory;
    private final ExecutorService loader = Executors.newSingleThreadExecutor(
            // Opening a session runs ONNX Runtime native code; see nativeStackBytes().
            RuntimeManager.namedDaemonThreads("stream-able-ai-loader", RuntimeManager.nativeStackBytes()));
    private final Map<MicrophoneSettings.NoiseBackend, String> rejected =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    private volatile Status status = Status.off();
    private volatile MicrophoneSettings.NoiseBackend requested;
    private volatile MicrophoneSettings.NoiseBackend active;
    private int overloadedChecks;
    private long generation;

    /** Test seam: a manager whose backends come from {@code factory}. */
    public NoiseCancellationManager(NoiseCancellationStage stage, BackendFactory factory) {
        this.stage = stage;
        this.factory = factory;
        this.onnx = null;
    }

    public NoiseCancellationManager(RuntimeManager runtimes, NoiseCancellationStage stage) {
        this.stage = stage;
        this.factory = this::prepareFromRuntimes;
        this.onnx = runtimes.get(AudioInferenceRuntime.ID).map(AudioInferenceRuntime.class::cast)
                .orElseGet(() -> runtimes.register(new AudioInferenceRuntime(runtimes.context())));
        for (ModelSpec spec : List.of(ModelSpec.DPDFNET_48K, ModelSpec.DFN2_16K, ModelSpec.GTCRN_16K)) {
            ModelRuntime runtime = runtimes.get(spec.runtimeId()).map(ModelRuntime.class::cast)
                    .orElseGet(() -> runtimes.register(new ModelRuntime(runtimes.context(), spec)));
            models.put(spec.backend(), runtime);
        }
    }

    /** Fallback order starting from a preference. */
    public static List<MicrophoneSettings.NoiseBackend> fallbackOrder(MicrophoneSettings.NoiseBackend preferred) {
        List<MicrophoneSettings.NoiseBackend> all = List.of(MicrophoneSettings.NoiseBackend.DPDFNET,
                MicrophoneSettings.NoiseBackend.DEEPFILTERNET, MicrophoneSettings.NoiseBackend.GTCRN);
        if (preferred == MicrophoneSettings.NoiseBackend.AUTO) {
            return all;
        }
        return all.subList(all.indexOf(preferred), all.size());
    }

    public Status status() {
        Status current = status;
        if (current.state() == Status.State.ACTIVE || current.state() == Status.State.DEGRADED) {
            // Live numbers from the stage.
            return new Status(stage.isOverloaded() ? Status.State.DEGRADED : Status.State.ACTIVE,
                    stage.isOverloaded() ? "Falling behind; passing the voice through until inference catches up."
                            : current.detail(),
                    current.activeModel(), current.engineVersion(), stage.latencyMillis(),
                    stage.averageInferenceMillis(), stage.realTimeFactor(), current.fallbacks());
        }
        return current;
    }

    public NoiseCancellationStage stage() {
        return stage;
    }

    /** Reacts to a settings change: loads, switches or unloads as needed. */
    public synchronized void apply(MicrophoneSettings settings) {
        MicrophoneSettings.NoiseLevel level = settings.noise.level;
        if (level == MicrophoneSettings.NoiseLevel.OFF) {
            if (requested != null) {
                requested = null;
                active = null;
                generation++;
                stage.uninstall();
                status = Status.off();
            }
            return;
        }
        MicrophoneSettings.NoiseBackend preference = settings.noise.backend;
        if (preference == requested && status.state() != Status.State.UNAVAILABLE) {
            return;   // already loading or running the right thing
        }
        requested = preference;
        rejected.clear();
        load(fallbackOrder(preference), ++generation);
    }

    /** Explicit retry from the UI after an "unavailable" state. */
    public synchronized void retry(MicrophoneSettings settings) {
        requested = null;
        apply(settings);
    }

    private void load(List<MicrophoneSettings.NoiseBackend> candidates, long job) {
        status = new Status(Status.State.LOADING, "Preparing " + candidates.getFirst().displayName() + "...",
                "", "", 0, 0, 0, fallbackNotes());
        loader.execute(() -> {
            for (MicrophoneSettings.NoiseBackend candidate : candidates) {
                if (job != generation) {
                    return;   // superseded by a newer request
                }
                if (rejected.containsKey(candidate)) {
                    continue;
                }
                try {
                    NoiseCancellationStage.Backend backend = factory.prepare(candidate, line ->
                            status = new Status(Status.State.LOADING, line, "", "", 0, 0, 0, fallbackNotes()));
                    if (backend == null) {
                        return;
                    }
                    synchronized (this) {
                        if (job != generation) {
                            backend.close();
                            return;
                        }
                        stage.install(backend);
                        active = candidate;
                        overloadedChecks = 0;
                        status = new Status(Status.State.ACTIVE, "Running locally on this computer.",
                                backend.spec().name(), backend.engineVersion, stage.latencyMillis(), 0, 0,
                                fallbackNotes());
                    }
                    stage.closeRetired();
                    return;
                } catch (Exception e) {
                    String reason = describe(e);
                    rejected.put(candidate, reason);
                    StreamAbleLog.AUDIO.warn("{} unavailable ({}); trying the next backend.", candidate.displayName(), reason);
                }
            }
            synchronized (this) {
                if (job != generation) {
                    return;
                }
                stage.uninstall();
                active = null;
                status = new Status(Status.State.UNAVAILABLE,
                        "AI noise cancellation is unavailable; conventional processing continues. " + String.join(" ", fallbackNotes()),
                        "", "", 0, 0, 0, fallbackNotes());
            }
        });
    }

    private NoiseCancellationStage.Backend prepareFromRuntimes(MicrophoneSettings.NoiseBackend candidate,
                                                               java.util.function.Consumer<String> progress) throws Exception {
        ModelRuntime model = models.get(candidate);
        ModelSpec spec = model.spec();
        progress.accept("Installing the AI runtime (ONNX Runtime)...");
        onnx.ensureReady().get(30, TimeUnit.MINUTES);
        progress.accept("Downloading " + spec.name() + "...");
        model.ensureReady().get(30, TimeUnit.MINUTES);
        progress.accept("Loading " + spec.name() + "...");
        InferenceEngine engine = onnx.engine();
        SpectralModel loaded = engine.open(model.modelFile(), spec.contract(), 1);
        try {
            double rtf = benchmark(spec, loaded);
            if (rtf > MAX_WARMUP_RTF) {
                throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                        "too slow on this CPU (%.0f %% of real time)", rtf * 100));
            }
            StreamAbleLog.AUDIO.info("{}: warm-up real-time factor {}", spec.name(),
                    String.format(java.util.Locale.ROOT, "%.3f", rtf));
            loaded.resetState();
            return new NoiseCancellationStage.Backend(spec, loaded, "ONNX Runtime " + engine.version());
        } catch (Exception e) {
            loaded.close();
            throw e;
        }
    }

    /** Runs 50 frames of low-level noise and returns inference time / hop duration. */
    static double benchmark(ModelSpec spec, SpectralModel model) throws Exception {
        StreamingEnhancer enhancer = new StreamingEnhancer(spec, model);
        java.util.Random random = new java.util.Random(1);
        float[] hop = new float[spec.hopSize()];
        StreamingEnhancer.Output sink = (samples, length) -> { };
        for (int i = 0; i < 10; i++) {           // warm-up (JIT, allocator)
            fill(hop, random);
            enhancer.push(hop, 0, hop.length, sink);
        }
        long start = System.nanoTime();
        int frames = 50;
        for (int i = 0; i < frames; i++) {
            fill(hop, random);
            enhancer.push(hop, 0, hop.length, sink);
        }
        double perHopMillis = (System.nanoTime() - start) / 1e6 / frames;
        return perHopMillis / spec.hopMillis();
    }

    private static void fill(float[] data, java.util.Random random) {
        for (int i = 0; i < data.length; i++) {
            data[i] = (float) ((random.nextDouble() * 2 - 1) * 0.01);
        }
    }

    /**
     * Called by the DSP worker about once a second. Demotes a backend that keeps
     * missing its real-time budget to the next lighter one.
     */
    public synchronized void supervise(MicrophoneSettings settings) {
        MicrophoneSettings.NoiseBackend current = active;
        if (current == null || stage.frames() < 100) {
            return;
        }
        boolean struggling = stage.realTimeFactor() > DEMOTE_RTF || stage.isOverloaded();
        overloadedChecks = struggling ? overloadedChecks + 1 : 0;
        if (overloadedChecks >= 5) {
            overloadedChecks = 0;
            rejected.put(current, "could not keep up in real time");
            List<MicrophoneSettings.NoiseBackend> remaining = new ArrayList<>(fallbackOrder(current));
            remaining.remove(current);
            StreamAbleLog.AUDIO.warn("{} cannot keep up in real time; falling back.", current.displayName());
            if (remaining.isEmpty()) {
                stage.uninstall();
                active = null;
                status = new Status(Status.State.UNAVAILABLE,
                        "Noise cancellation cannot maintain real-time processing on this CPU; it has been turned off.",
                        "", "", 0, 0, 0, fallbackNotes());
                return;
            }
            load(remaining, ++generation);
        }
    }

    private List<String> fallbackNotes() {
        List<String> notes = new ArrayList<>();
        synchronized (rejected) {
            rejected.forEach((backend, reason) -> notes.add(backend.displayName() + ": " + reason + "."));
        }
        return notes;
    }

    /** Active backend choice, or {@code null}. */
    public MicrophoneSettings.NoiseBackend activeBackend() {
        return active;
    }

    private static String describe(Throwable e) {
        Throwable cause = e;
        while ((cause instanceof java.util.concurrent.ExecutionException
                || cause instanceof java.util.concurrent.CompletionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    public AudioInferenceRuntime onnxRuntime() {
        return onnx;
    }

    public ModelRuntime model(MicrophoneSettings.NoiseBackend backend) {
        return models.get(backend);
    }

    @Override
    public void close() {
        generation++;
        loader.shutdownNow();
        stage.uninstall();
        stage.closeRetired();
    }
}
