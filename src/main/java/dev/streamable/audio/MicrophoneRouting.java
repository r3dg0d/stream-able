package dev.streamable.audio;

import dev.streamable.config.MicrophoneSettings;

/**
 * Hand-off point for microphone audio captured by an optional voice-chat
 * integration (Plasmo Voice, Simple Voice Chat), so it can pass through the one
 * canonical microphone chain instead of going straight to the mixer.
 */
public final class MicrophoneRouting {

    /** Accepts interleaved 16-bit PCM; returns {@code true} when it took the audio. */
    @FunctionalInterface
    public interface Sink {
        boolean accept(MicrophoneSettings.Source origin, short[] samples, int channels, int sampleRate);
    }

    private static volatile Sink sink;

    private MicrophoneRouting() {
    }

    public static void setSink(Sink value) {
        sink = value;
    }

    /**
     * Offers a voice-chat mod's microphone audio to Stream-able's chain.
     *
     * @return {@code true} when that mod is the selected microphone source and the audio was taken
     */
    public static boolean route(MicrophoneSettings.Source origin, short[] samples, int channels, int sampleRate) {
        Sink current = sink;
        return current != null && current.accept(origin, samples, channels, sampleRate);
    }
}
