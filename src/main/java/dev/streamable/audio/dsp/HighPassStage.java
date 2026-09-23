package dev.streamable.audio.dsp;

import dev.streamable.config.MicrophoneSettings;

/**
 * DC removal plus an adjustable high-pass.
 *
 * <p>The DC blocker (a 5 Hz one-pole high-pass) always runs: an offset wastes
 * headroom and makes the gate and compressor misjudge level. The high-pass is
 * optional - 80 Hz at 12 dB/octave by default, low enough to leave normal
 * voices their body while removing desk thumps, fans' rumble and breath pops.</p>
 */
public final class HighPassStage implements AudioStage {

    private final double sampleRate;
    private final Biquad first = new Biquad();
    private final Biquad second = new Biquad();
    private boolean enabled;
    private boolean fourthOrder;
    private double dcX;
    private double dcY;
    private double dcR;
    private double configuredFrequency = -1;
    private int configuredSlope = -1;

    public HighPassStage(double sampleRate) {
        this.sampleRate = sampleRate;
        this.dcR = Math.exp(-2 * Math.PI * 5.0 / sampleRate);
    }

    @Override
    public String id() {
        return "highpass";
    }

    @Override
    public void configure(MicrophoneSettings settings) {
        MicrophoneSettings.HighPass hp = settings.highPass;
        enabled = hp.enabled;
        if (hp.frequencyHz != configuredFrequency || hp.slopeDbPerOctave != configuredSlope) {
            configuredFrequency = hp.frequencyHz;
            configuredSlope = hp.slopeDbPerOctave;
            fourthOrder = hp.slopeDbPerOctave >= 24;
            if (fourthOrder) {
                first.set(BiquadDesign.highPass(hp.frequencyHz, BiquadDesign.BUTTERWORTH_4_Q[0], sampleRate));
                second.set(BiquadDesign.highPass(hp.frequencyHz, BiquadDesign.BUTTERWORTH_4_Q[1], sampleRate));
            } else {
                first.set(BiquadDesign.highPass(hp.frequencyHz, BiquadDesign.BUTTERWORTH_2_Q, sampleRate));
                second.setIdentity();
            }
        }
    }

    /** DC removal is unconditional; "enabled" refers to the adjustable high-pass. */
    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void process(float[] samples, int offset, int length) {
        double x1 = dcX;
        double y1 = dcY;
        for (int i = offset; i < offset + length; i++) {
            double x = samples[i];
            double y = x - x1 + dcR * y1;
            x1 = x;
            y1 = y;
            samples[i] = (float) y;
        }
        dcX = x1;
        dcY = Math.abs(y1) < 1e-25 ? 0 : y1;
        if (enabled) {
            first.process(samples, offset, length);
            if (fourthOrder) {
                second.process(samples, offset, length);
            }
        }
    }

    @Override
    public void reset() {
        dcX = 0;
        dcY = 0;
        first.reset();
        second.reset();
    }
}
