package dev.streamable.audio.dsp;

import dev.streamable.config.MicrophoneSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The one canonical microphone processing chain.
 *
 * <pre>
 *   input gain -> DC / high-pass -> [AI noise cancellation] -> gate/expander
 *     -> EQ -> de-esser -> compressor -> AGC -> limiter -> output gain
 * </pre>
 *
 * <p>Recording and streaming both receive its output through the program
 * mixer; there is no second implementation anywhere. The only reordering
 * offered is EQ before or after the compressor, which is musically meaningful
 * and can never produce a broken chain.</p>
 *
 * <p>Stages can be bypassed individually for A/B listening without changing
 * their saved settings. Single-threaded: owned by the DSP worker.</p>
 */
public final class MicrophoneChain {

    public static final int SAMPLE_RATE = 48_000;

    private final GainStage inputGain = new GainStage(GainStage.Position.INPUT, SAMPLE_RATE);
    private final HighPassStage highPass = new HighPassStage(SAMPLE_RATE);
    private final AudioStage noiseCancellation;
    private final GateStage gate = new GateStage(SAMPLE_RATE);
    private final EqualizerStage eq = new EqualizerStage(SAMPLE_RATE);
    private final DeEsserStage deEsser = new DeEsserStage(SAMPLE_RATE);
    private final CompressorStage compressor = new CompressorStage(SAMPLE_RATE);
    private final AgcStage agc = new AgcStage(SAMPLE_RATE);
    private final LimiterStage limiter = new LimiterStage(SAMPLE_RATE);
    private final GainStage outputGain = new GainStage(GainStage.Position.OUTPUT, SAMPLE_RATE);

    private final LevelMeter inputMeter = new LevelMeter(SAMPLE_RATE);
    private final LevelMeter outputMeter = new LevelMeter(SAMPLE_RATE);
    private final Set<String> bypassed = Collections.synchronizedSet(new HashSet<>());
    private List<AudioStage> order = List.of();
    private boolean processingEnabled = true;
    private int configuredRevision = Integer.MIN_VALUE;

    /**
     * @param noiseCancellation the AI stage (may be a pass-through when AI is unavailable)
     */
    public MicrophoneChain(AudioStage noiseCancellation) {
        this.noiseCancellation = noiseCancellation;
    }

    /** Re-reads settings when their revision changed. Cheap otherwise. */
    public void configureIfChanged(MicrophoneSettings settings) {
        if (settings.revision == configuredRevision) {
            return;
        }
        configuredRevision = settings.revision;
        configure(settings);
    }

    public void configure(MicrophoneSettings settings) {
        processingEnabled = settings.processingEnabled;
        List<AudioStage> stages = new ArrayList<>();
        stages.add(inputGain);
        stages.add(highPass);
        stages.add(noiseCancellation);
        stages.add(gate);
        if (settings.eqPlacement == MicrophoneSettings.EqPlacement.BEFORE_COMPRESSOR) {
            stages.add(eq);
            stages.add(deEsser);
            stages.add(compressor);
        } else {
            stages.add(deEsser);
            stages.add(compressor);
            stages.add(eq);
        }
        stages.add(agc);
        stages.add(limiter);
        stages.add(outputGain);
        for (AudioStage stage : stages) {
            stage.configure(settings);
        }
        order = List.copyOf(stages);
    }

    /** Processes one block in place. */
    public void process(float[] samples, int offset, int length) {
        inputMeter.process(samples, offset, length);
        if (processingEnabled) {
            for (AudioStage stage : order) {
                if (stage.isEnabled() && !bypassed.contains(stage.id())) {
                    stage.process(samples, offset, length);
                }
            }
        }
        outputMeter.process(samples, offset, length);
    }

    /** Bypasses one stage for A/B listening; does not change saved settings. */
    public void setBypassed(String stageId, boolean bypass) {
        if (bypass) {
            bypassed.add(stageId);
        } else {
            bypassed.remove(stageId);
        }
    }

    public boolean isBypassed(String stageId) {
        return bypassed.contains(stageId);
    }

    /** Total algorithmic latency of the enabled stages, in samples. */
    public int latencySamples() {
        if (!processingEnabled) {
            return 0;
        }
        int total = 0;
        for (AudioStage stage : order) {
            if (stage.isEnabled() && !bypassed.contains(stage.id())) {
                total += stage.latencySamples();
            }
        }
        return total;
    }

    public double latencyMillis() {
        return latencySamples() * 1000.0 / SAMPLE_RATE;
    }

    public void reset() {
        for (AudioStage stage : order) {
            stage.reset();
        }
        inputMeter.reset();
        outputMeter.reset();
    }

    public LevelMeter.Reading inputLevel() {
        return inputMeter.reading();
    }

    public LevelMeter.Reading outputLevel() {
        return outputMeter.reading();
    }

    public GateStage gate() {
        return gate;
    }

    public CompressorStage compressor() {
        return compressor;
    }

    public LimiterStage limiter() {
        return limiter;
    }

    public AgcStage agc() {
        return agc;
    }

    public DeEsserStage deEsser() {
        return deEsser;
    }

    public List<AudioStage> stages() {
        return order;
    }
}
