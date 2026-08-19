package dev.streamable.audio;

/**
 * Converts voice-chat PCM into the mixer's format.
 *
 * <p>Plasmo Voice hands out {@code short[]} samples that may be mono or stereo
 * and are nominally 48 kHz, while the program mix is fixed at 48 kHz stereo
 * little-endian {@code s16}. This class does the three adjustments that can be
 * needed - channel duplication, resampling and endian-correct packing - as pure
 * functions so they can be tested without a voice server.</p>
 *
 * <p>Resampling is linear interpolation. That is more than adequate for speech
 * and costs almost nothing; in practice it is a no-op because Plasmo Voice
 * already runs at 48 kHz, but handling the mismatch means an unusual device
 * configuration produces correct audio instead of chipmunk voices.</p>
 */
public final class VoicePcmConverter {

    private VoicePcmConverter() {
    }

    /**
     * Converts voice samples to 48 kHz stereo little-endian {@code s16} bytes.
     *
     * @param samples    interleaved PCM from the voice client
     * @param channels   {@code 1} for mono, {@code 2} for interleaved stereo
     * @param sampleRate the samples' rate in Hz
     * @return bytes ready for {@link AudioMixer#submit}
     */
    public static byte[] toMixerFormat(short[] samples, int channels, int sampleRate) {
        if (samples == null || samples.length == 0) {
            return new byte[0];
        }
        int sourceChannels = channels == 2 ? 2 : 1;
        int frames = samples.length / sourceChannels;
        if (frames == 0) {
            return new byte[0];
        }

        int targetRate = AudioMixer.SAMPLE_RATE;
        int targetFrames = sampleRate == targetRate || sampleRate <= 0
                ? frames
                : (int) Math.max(1, Math.round(frames * (double) targetRate / sampleRate));

        byte[] out = new byte[targetFrames * 2 * 2];   // stereo, 2 bytes per sample
        for (int frame = 0; frame < targetFrames; frame++) {
            int left;
            int right;
            if (targetFrames == frames) {
                left = samples[frame * sourceChannels];
                right = sourceChannels == 2 ? samples[frame * sourceChannels + 1] : left;
            } else {
                // Linear interpolation between the two neighbouring source frames.
                double position = frame * (frames - 1) / (double) Math.max(1, targetFrames - 1);
                int index = (int) position;
                double fraction = position - index;
                int next = Math.min(index + 1, frames - 1);
                left = interpolate(samples[index * sourceChannels], samples[next * sourceChannels], fraction);
                right = sourceChannels == 2
                        ? interpolate(samples[index * sourceChannels + 1],
                        samples[next * sourceChannels + 1], fraction)
                        : left;
            }
            int offset = frame * 4;
            out[offset] = (byte) (left & 0xFF);
            out[offset + 1] = (byte) ((left >> 8) & 0xFF);
            out[offset + 2] = (byte) (right & 0xFF);
            out[offset + 3] = (byte) ((right >> 8) & 0xFF);
        }
        return out;
    }

    private static int interpolate(short from, short to, double fraction) {
        return (int) Math.round(from + (to - from) * fraction);
    }
}
