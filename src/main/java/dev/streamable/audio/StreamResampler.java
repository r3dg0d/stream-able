package dev.streamable.audio;

import dev.streamable.audio.dsp.SincResampler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts chunked 16-bit audio streams (for example a browser page's Web Audio
 * output) to the mixer's 48 kHz stereo format, keeping per-stream resampler
 * state so chunk boundaries join seamlessly.
 *
 * <p>Streams already at 48 kHz pass straight through. Streams that fall idle
 * are forgotten so their state does not accumulate.</p>
 */
public final class StreamResampler {

    private static final long IDLE_NANOS = 10_000_000_000L;

    private static final class State {
        final int rate;
        final SincResampler left;
        final SincResampler right;
        float[] inL = new float[0];
        float[] inR = new float[0];
        float[] outL = new float[0];
        float[] outR = new float[0];
        long lastUse;

        State(int rate) {
            this.rate = rate;
            this.left = new SincResampler(rate, AudioMixer.SAMPLE_RATE);
            this.right = new SincResampler(rate, AudioMixer.SAMPLE_RATE);
        }
    }

    private final Map<Object, State> states = new ConcurrentHashMap<>();

    /**
     * @param samples interleaved 16-bit samples
     * @return 48 kHz stereo little-endian 16-bit PCM
     */
    public byte[] toMixerFormat(Object stream, short[] samples, int channels, int sampleRate) {
        int ch = channels == 2 ? 2 : 1;
        int frames = samples.length / ch;
        long now = System.nanoTime();
        if (states.size() > 32) {
            states.values().removeIf(s -> now - s.lastUse > IDLE_NANOS);
        }
        if (sampleRate == AudioMixer.SAMPLE_RATE) {
            return VoicePcmConverter.toMixerFormat(samples, ch, sampleRate);
        }
        State state = states.compute(stream, (k, old) -> old == null || old.rate != sampleRate ? new State(sampleRate) : old);
        synchronized (state) {
            state.lastUse = now;
            if (state.inL.length < frames) {
                state.inL = new float[frames];
                state.inR = new float[frames];
            }
            for (int f = 0; f < frames; f++) {
                state.inL[f] = samples[f * ch] / 32768f;
                state.inR[f] = samples[f * ch + (ch - 1)] / 32768f;
            }
            int capacity = state.left.maxOutput(frames) + 4;
            if (state.outL.length < capacity) {
                state.outL = new float[capacity];
                state.outR = new float[capacity];
            }
            int nl = state.left.process(state.inL, 0, frames, state.outL);
            int nr = state.right.process(state.inR, 0, frames, state.outR);
            int n = Math.min(nl, nr);
            byte[] pcm = new byte[n * 4];
            for (int i = 0; i < n; i++) {
                putSample(pcm, i * 4, state.outL[i]);
                putSample(pcm, i * 4 + 2, state.outR[i]);
            }
            return pcm;
        }
    }

    private static void putSample(byte[] out, int at, float value) {
        int s = Math.round(Math.clamp(value, -1f, 1f) * 32767f);
        out[at] = (byte) (s & 0xFF);
        out[at + 1] = (byte) ((s >> 8) & 0xFF);
    }
}
