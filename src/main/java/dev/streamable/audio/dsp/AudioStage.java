package dev.streamable.audio.dsp;

import dev.streamable.config.MicrophoneSettings;

/**
 * One stage of the microphone chain: mono float samples at
 * {@link MicrophoneChain#SAMPLE_RATE}, processed in place.
 *
 * <p>Stages are single-threaded (the DSP worker owns them), allocate nothing
 * in {@link #process}, and are reconfigured between blocks - never mid-block.</p>
 */
public interface AudioStage {

    /** Stable identifier used by bypass/A-B controls and diagnostics. */
    String id();

    /** Reads its parameters from the settings. Called on the DSP thread. */
    void configure(MicrophoneSettings settings);

    /** Whether the settings enable this stage. */
    boolean isEnabled();

    void process(float[] samples, int offset, int length);

    /** Clears filter and envelope state (device change, long pause). */
    void reset();

    /** Delay this stage adds, in samples at the chain rate. */
    default int latencySamples() {
        return 0;
    }

    /** Current gain reduction for meters, in dB (0 when not applicable). */
    default double gainReductionDb() {
        return 0;
    }
}
