package dev.streamable.audio.mic;

import dev.streamable.config.MicrophoneSettings;

/**
 * Turns interleaved stereo capture into the chain's mono signal.
 *
 * <p>Microphones are mono. A USB interface with a single XLR microphone on
 * input 1 delivers it on the left channel only; simply averaging would then
 * cost 6 dB and put the room noise of an empty input into the mix. In AUTO
 * mode the selector tracks each channel's level and uses only the live one
 * when the other is more than 30 dB quieter; otherwise it mixes both. The
 * resulting mono signal is centred in the stereo program later (equal in both
 * channels), never left-only.</p>
 */
public final class ChannelSelector {

    private double leftPower;
    private double rightPower;

    public int toMono(short[] interleaved, int frames, int channels, MicrophoneSettings.InputChannel mode, float[] out) {
        if (channels == 1) {
            for (int i = 0; i < frames; i++) {
                out[i] = interleaved[i] / 32768f;
            }
            return frames;
        }
        double l = 0;
        double r = 0;
        for (int i = 0; i < frames; i++) {
            double left = interleaved[i * channels];
            double right = interleaved[i * channels + 1];
            l += left * left;
            r += right * right;
        }
        leftPower = leftPower * 0.9 + l / Math.max(1, frames) * 0.1;
        rightPower = rightPower * 0.9 + r / Math.max(1, frames) * 0.1;
        MicrophoneSettings.InputChannel effective = mode;
        if (mode == MicrophoneSettings.InputChannel.AUTO) {
            effective = choose(leftPower, rightPower);
        }
        for (int i = 0; i < frames; i++) {
            float left = interleaved[i * channels] / 32768f;
            float right = interleaved[i * channels + 1] / 32768f;
            out[i] = switch (effective) {
                case LEFT -> left;
                case RIGHT -> right;
                default -> (left + right) * 0.5f;
            };
        }
        return frames;
    }

    /** AUTO decision: a channel 30 dB below the other is treated as unused. */
    static MicrophoneSettings.InputChannel choose(double leftPower, double rightPower) {
        double ratio = 1000.0;   // 30 dB in power
        if (leftPower > rightPower * ratio) {
            return MicrophoneSettings.InputChannel.LEFT;
        }
        if (rightPower > leftPower * ratio) {
            return MicrophoneSettings.InputChannel.RIGHT;
        }
        return MicrophoneSettings.InputChannel.MIX;
    }

    /** What AUTO is currently doing, for the Audio page. */
    public MicrophoneSettings.InputChannel autoDecision() {
        return choose(leftPower, rightPower);
    }
}
