package dev.streamable.audio.ai;

import dev.streamable.StreamAbleLog;
import dev.streamable.audio.dsp.AudioStage;
import dev.streamable.audio.dsp.Db;
import dev.streamable.audio.dsp.MicrophoneChain;
import dev.streamable.audio.dsp.SincResampler;
import dev.streamable.config.MicrophoneSettings;

import java.util.concurrent.atomic.AtomicReference;

/**
 * AI noise cancellation as a chain stage, at a fixed, known latency.
 *
 * <h2>Timing model</h2>
 * <p>The stage keeps two rings indexed by <em>input time</em>: the dry input
 * and the enhanced ("wet") output of the model. When sample {@code t} enters,
 * the stage emits position {@code p = t - L}, where {@code L} is the time the
 * model pipeline needs before position {@code p} is final (STFT framing plus
 * resampler look-ahead). The emitted sample blends {@code wet[p]} with
 * {@code dry[p - modelDelay]}, because the network's output lags its input by
 * its look-ahead. Consequences:</p>
 * <ul>
 *   <li>The stage's latency is constant ({@link #latencySamples()}): it cannot
 *       grow no matter how slow inference becomes.</li>
 *   <li>If a wet sample is not ready - inference late, the worker overloaded,
 *       the backend bypassed or swapping - the time-aligned dry sample is used,
 *       with a short crossfade. The microphone never drops out.</li>
 *   <li>Dry/wet blending ("strength", "voice preservation") is sample-exact, so
 *       it never comb-filters.</li>
 * </ul>
 *
 * <p>Models at 16 kHz are fed through band-limited resamplers; the index
 * alignment is preserved through both conversions. Single-threaded: owned by
 * the DSP worker. The backend is installed from the loader thread through
 * {@link #install}, and swapped in at the next block boundary.</p>
 */
public final class NoiseCancellationStage implements AudioStage {

    private static final int RING = 1 << 17;          // ~2.7 s at 48 kHz
    private static final int MASK = RING - 1;
    private static final int RATE = MicrophoneChain.SAMPLE_RATE;
    private static final int BLOCK = 480;              // DSP worker block size

    /** A loaded model ready to run, handed over by the loader thread. */
    public static final class Backend implements AutoCloseable {
        final ModelSpec spec;
        final SpectralModel model;
        final StreamingEnhancer enhancer;
        final SincResampler down;
        final SincResampler up;
        final String engineVersion;

        public Backend(ModelSpec spec, SpectralModel model, String engineVersion) {
            this.spec = spec;
            this.model = model;
            this.enhancer = new StreamingEnhancer(spec, model);
            boolean resample = spec.sampleRate() != RATE;
            this.down = resample ? new SincResampler(RATE, spec.sampleRate()) : null;
            this.up = resample ? new SincResampler(spec.sampleRate(), RATE) : null;
            this.engineVersion = engineVersion;
        }

        public ModelSpec spec() {
            return spec;
        }

        int ratio() {
            return RATE / spec.sampleRate();
        }

        /** Samples at 48 kHz before a wet position is final. */
        int availabilityLag() {
            int ratio = ratio();
            int framing = (spec.fftSize() - spec.hopSize()) * ratio;
            int hop48 = spec.hopSize() * ratio;
            int slack = BLOCK % hop48 == 0 ? 0 : hop48;
            int resampling = 0;
            if (down != null) {
                resampling = (int) Math.ceil(down.latencyOutputSamples()) * ratio   // down look-ahead, 48k input
                        + (int) Math.ceil(up.latencyOutputSamples())                 // up look-ahead, in 48k output
                        + 2 * ratio;
            }
            return framing + slack + resampling;
        }

        int modelDelay48() {
            return spec.modelDelaySamples() * ratio();
        }

        @Override
        public void close() {
            model.close();
        }
    }

    private final float[] dry = new float[RING];
    private final float[] wet = new float[RING];
    private final float[] modelInput = new float[4096];
    private final float[] upOutput = new float[8192];
    private final AtomicReference<Backend> pending = new AtomicReference<>();
    private final AtomicReference<Backend> retired = new AtomicReference<>();

    private Backend backend;
    private long inputCount;
    /** Wet positions below this are final. */
    private long wetEnd;
    /** Input position where the current feeding run started. */
    private long feedStart;
    private boolean feeding;
    private int lag;
    private int modelDelay;

    private boolean enabled;
    private boolean bypass;
    private volatile boolean overloaded;
    private double alphaFloor;          // minimum dry share, from the attenuation limit
    private double voicePreservation;
    private double wetMix;              // smoothed 0..1 share of the enhanced path
    private final double mixSmoothing = Db.coefficient(8, RATE);

    // Speech tracking for voice preservation.
    private double wetPower;
    private double floorDb = -60;
    private double speech;
    private final double powerCoeff = Db.coefficient(20, RATE);

    // Diagnostics.
    private volatile long lateSamples;
    private volatile long inferenceFailures;
    private volatile double averageInferenceMillis;
    private volatile double realTimeFactor;
    private volatile String activeName = "";
    private volatile String failure = "";
    private long framesAtLastCheck;

    @Override
    public String id() {
        return "ai";
    }

    /** Hands a loaded backend to the DSP thread. The previous one is retired and closed later. */
    public void install(Backend next) {
        removeRequested = false;
        Backend previous = pending.getAndSet(next);
        if (previous != null) {
            previous.close();
        }
    }

    /** Removes the backend (AI turned off or unavailable) at the next block. */
    public void uninstall() {
        Backend previous = pending.getAndSet(null);
        if (previous != null) {
            previous.close();
        }
        removeRequested = true;
    }

    private volatile boolean removeRequested;

    /** Closes a backend replaced on the DSP thread. Called from the loader thread. */
    public void closeRetired() {
        Backend old = retired.getAndSet(null);
        if (old != null) {
            old.close();
        }
    }

    public boolean hasBackend() {
        return backend != null || pending.get() != null;
    }

    public ModelSpec activeSpec() {
        Backend current = backend;
        return current == null ? null : current.spec;
    }

    @Override
    public void configure(MicrophoneSettings settings) {
        MicrophoneSettings.NoiseCancellation noise = settings.noise;
        enabled = noise.level != MicrophoneSettings.NoiseLevel.OFF;
        bypass = noise.bypass;
        double limitDb = noise.strengthOverrideDb >= 0 ? noise.strengthOverrideDb : attenuationLimitDb(noise.level);
        alphaFloor = Db.toLinear(-limitDb);
        voicePreservation = noise.voicePreservation >= 0 ? noise.voicePreservation : defaultVoicePreservation(noise.level);
    }

    /**
     * Maximum noise reduction per level. These are real model parameters: the
     * dry signal is blended back at this attenuation (DPDFNet's
     * {@code attn_limit_db}), which bounds how hard the model may suppress.
     */
    public static double attenuationLimitDb(MicrophoneSettings.NoiseLevel level) {
        return switch (level) {
            case OFF -> 0;
            case LIGHT -> 12;
            case BALANCED -> 20;
            case STRONG -> 40;
        };
    }

    /** Share of the dry voice restored while speech is present, per level. */
    public static double defaultVoicePreservation(MicrophoneSettings.NoiseLevel level) {
        return switch (level) {
            case OFF -> 1;
            case LIGHT -> 0.25;
            case BALANCED -> 0.15;
            case STRONG -> 0.05;
        };
    }

    @Override
    public boolean isEnabled() {
        return enabled && (backend != null || pending.get() != null);
    }

    /** Asks the stage to stop running inference (DSP worker behind); output continues dry. */
    public void setOverloaded(boolean value) {
        overloaded = value;
    }

    public boolean isOverloaded() {
        return overloaded;
    }

    @Override
    public int latencySamples() {
        Backend current = backend;
        if (current != null) {
            return lag + modelDelay;
        }
        Backend next = pending.get();
        return next == null ? 0 : next.availabilityLag() + next.modelDelay48();
    }

    public double latencyMillis() {
        return latencySamples() * 1000.0 / RATE;
    }

    private void swapIfPending() {
        if (removeRequested) {
            removeRequested = false;
            Backend old = backend;
            backend = null;
            feeding = false;
            activeName = "";
            if (old != null) {
                Backend unclosed = retired.getAndSet(old);
                if (unclosed != null) {
                    unclosed.close();
                }
            }
            return;
        }
        Backend next = pending.getAndSet(null);
        if (next == null) {
            return;
        }
        Backend old = backend;
        backend = next;
        lag = next.availabilityLag();
        modelDelay = next.modelDelay48();
        feeding = false;
        activeName = next.spec.name();
        framesAtLastCheck = 0;
        if (old != null) {
            Backend unclosed = retired.getAndSet(old);
            if (unclosed != null) {
                unclosed.close();
            }
        }
        StreamAbleLog.AUDIO.info("Noise cancellation: {} active ({} ms latency).", activeName,
                String.format(java.util.Locale.ROOT, "%.1f", latencyMillis()));
    }

    @Override
    public void process(float[] samples, int offset, int length) {
        swapIfPending();
        Backend current = backend;
        if (current == null) {
            return;
        }
        long blockStart = inputCount;
        for (int i = 0; i < length; i++) {
            dry[(int) ((blockStart + i) & MASK)] = samples[offset + i];
        }
        inputCount += length;

        boolean run = !bypass && !overloaded;
        if (run) {
            if (!feeding) {
                // (Re)start the model pipeline aligned to this block.
                current.enhancer.reset();
                if (current.down != null) {
                    current.down.reset();
                    current.up.reset();
                }
                feedStart = blockStart;
                wetEnd = blockStart;
                feeding = true;
            }
            try {
                feed(current, samples, offset, length);
            } catch (Exception e) {
                inferenceFailures++;
                failure = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                feeding = false;
                if (inferenceFailures == 1 || inferenceFailures % 100 == 0) {
                    StreamAbleLog.AUDIO.warn("Noise cancellation inference failed ({}); passing the voice through.", failure);
                }
            }
        } else {
            feeding = false;
        }
        emit(samples, offset, length, blockStart);
        updateStatistics(current);
    }

    private void feed(Backend current, float[] samples, int offset, int length) throws Exception {
        if (current.down == null) {
            current.enhancer.push(samples, offset, length, this::appendWet);
            return;
        }
        int produced = current.down.process(samples, offset, length, modelInput);
        current.enhancer.push(modelInput, 0, produced, (hop, count) -> {
            int up = current.up.process(hop, 0, count, upOutput);
            appendWet(upOutput, up);
        });
    }

    private void appendWet(float[] data, int count) {
        for (int i = 0; i < count; i++) {
            wet[(int) ((wetEnd + i) & MASK)] = data[i];
        }
        wetEnd += count;
    }

    private void emit(float[] samples, int offset, int length, long blockStart) {
        for (int i = 0; i < length; i++) {
            long t = blockStart + i;
            long p = t - lag;
            long drySource = p - modelDelay;
            float dryAligned = drySource >= 0 ? dry[(int) (drySource & MASK)] : 0f;
            boolean wetReady = feeding && p >= feedStart && p < wetEnd && p >= 0;
            float wetSample = wetReady ? wet[(int) (p & MASK)] : dryAligned;
            if (!wetReady && feeding && p >= feedStart) {
                lateSamples++;
            }

            // Voice preservation: blend more of the dry voice while speech is present.
            wetPower = wetSample * (double) wetSample + (wetPower - wetSample * (double) wetSample) * powerCoeff;
            double wetDb = Db.fromPower(wetPower);
            floorDb = wetDb < floorDb ? wetDb : floorDb + 0.00002;
            double speechTarget = Math.clamp((wetDb - floorDb - 6) / 12, 0, 1);
            speech += (speechTarget - speech) * 0.002;
            double alpha = Math.max(alphaFloor, voicePreservation * speech);
            float enhanced = (float) (alpha * dryAligned + (1 - alpha) * wetSample);

            double mixTarget = wetReady ? 1 : 0;
            wetMix = mixTarget + (wetMix - mixTarget) * mixSmoothing;
            samples[offset + i] = (float) (wetMix * enhanced + (1 - wetMix) * dryAligned);
        }
    }

    private void updateStatistics(Backend current) {
        StreamingEnhancer enhancer = current.enhancer;
        averageInferenceMillis = enhancer.averageInferenceMillis();
        double hopMillis = current.spec.hopMillis();
        realTimeFactor = hopMillis > 0 ? enhancer.lastInferenceMillis() / hopMillis * 0.2 + realTimeFactor * 0.8 : 0;
    }

    /** Frames processed since the backend was installed. */
    public long frames() {
        Backend current = backend;
        return current == null ? 0 : current.enhancer.frames();
    }

    /** Frames since the last call, for the manager's "too slow" check. */
    public long framesSinceCheck() {
        long frames = frames();
        long since = frames - framesAtLastCheck;
        framesAtLastCheck = frames;
        return since;
    }

    public double averageInferenceMillis() {
        return averageInferenceMillis;
    }

    /** Smoothed inference time divided by hop duration; above ~0.8 it cannot keep up. */
    public double realTimeFactor() {
        return realTimeFactor;
    }

    /** Samples that fell back to dry because the model output was not ready in time. */
    public long lateSamples() {
        return lateSamples;
    }

    public long inferenceFailures() {
        return inferenceFailures;
    }

    public String activeName() {
        return activeName;
    }

    public String lastFailure() {
        return failure;
    }

    public boolean isBypassed() {
        return bypass;
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(dry, 0f);
        java.util.Arrays.fill(wet, 0f);
        inputCount = 0;
        wetEnd = 0;
        feeding = false;
        wetMix = 0;
        speech = 0;
        floorDb = -60;
        wetPower = 0;
    }
}
