package dev.streamable.audio.mic;

/**
 * Receives 48 kHz mono blocks from the DSP worker. Implementations must be
 * quick and must copy what they keep: the array is reused.
 */
@FunctionalInterface
public interface BlockListener {
    void onBlock(float[] samples, int length);
}
