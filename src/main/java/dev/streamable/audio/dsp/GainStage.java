package dev.streamable.audio.dsp;

import dev.streamable.config.MicrophoneSettings;

/** Digital gain with a short ramp on change, so moving the slider never clicks. */
public final class GainStage implements AudioStage {

    public enum Position { INPUT, OUTPUT }

    private final Position position;
    private final double rampCoeff;
    private double targetLinear = 1.0;
    private double currentLinear = 1.0;

    public GainStage(Position position, double sampleRate) {
        this.position = position;
        this.rampCoeff = Db.coefficient(10, sampleRate);
    }

    @Override
    public String id() {
        return position == Position.INPUT ? "input-gain" : "output-gain";
    }

    @Override
    public void configure(MicrophoneSettings settings) {
        double db = position == Position.INPUT ? settings.inputGainDb : settings.outputGainDb;
        targetLinear = Db.toLinear(db);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void process(float[] samples, int offset, int length) {
        if (currentLinear == targetLinear && targetLinear == 1.0) {
            return;
        }
        for (int i = offset; i < offset + length; i++) {
            currentLinear = targetLinear + (currentLinear - targetLinear) * rampCoeff;
            samples[i] = (float) (samples[i] * currentLinear);
        }
        if (Math.abs(currentLinear - targetLinear) < 1e-6) {
            currentLinear = targetLinear;
        }
    }

    @Override
    public void reset() {
        currentLinear = targetLinear;
    }
}
