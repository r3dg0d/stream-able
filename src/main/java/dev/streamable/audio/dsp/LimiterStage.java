package dev.streamable.audio.dsp;

import dev.streamable.config.MicrophoneSettings;

/**
 * A look-ahead brickwall limiter - the last line of defence against clipping.
 *
 * <p>The input is delayed by the look-ahead ({@value #LOOKAHEAD_MS} ms); the
 * gain for each output sample is the minimum gain required by every sample in
 * the window ahead of it, approached with an attack equal to the look-ahead so
 * the gain is already down when a peak arrives. A final hard clamp at the
 * ceiling guarantees the promise even for pathological input.</p>
 */
public final class LimiterStage implements AudioStage {

    public static final double LOOKAHEAD_MS = 1.5;

    private final double sampleRate;
    private final int lookahead;
    private final float[] delay;
    private final double[] requiredGain;
    private int index;
    private boolean enabled;
    private double ceiling = Db.toLinear(-1.0);
    private double releaseCoeff;
    private double gain = 1.0;
    private double minGainInBlock = 1.0;

    public LimiterStage(double sampleRate) {
        this.sampleRate = sampleRate;
        this.lookahead = Math.max(1, (int) Math.round(LOOKAHEAD_MS * 0.001 * sampleRate));
        this.delay = new float[lookahead];
        this.requiredGain = new double[lookahead];
        java.util.Arrays.fill(requiredGain, 1.0);
    }

    @Override
    public String id() {
        return "limiter";
    }

    @Override
    public void configure(MicrophoneSettings settings) {
        enabled = settings.limiter.enabled;
        ceiling = Db.toLinear(settings.limiter.ceilingDb);
        releaseCoeff = Db.coefficient(settings.limiter.releaseMs, sampleRate);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public int latencySamples() {
        return lookahead;
    }

    @Override
    public double gainReductionDb() {
        return -Db.fromLinear(minGainInBlock);
    }

    public double ceilingLinear() {
        return ceiling;
    }

    @Override
    public void process(float[] samples, int offset, int length) {
        double attackStep = 1.0 / lookahead;
        double blockMin = 1.0;
        for (int i = offset; i < offset + length; i++) {
            float input = samples[i];
            double magnitude = Math.abs(input);
            requiredGain[index] = magnitude > ceiling ? ceiling / magnitude : 1.0;
            float delayed = delay[index];
            delay[index] = input;
            index = (index + 1) % lookahead;

            // The gain needed by anything within the look-ahead window.
            double target = 1.0;
            for (double g : requiredGain) {
                if (g < target) {
                    target = g;
                }
            }
            if (target < gain) {
                // Ramp down across the look-ahead so the peak meets reduced gain.
                gain = Math.max(target, gain - attackStep);
            } else {
                gain = target + (gain - target) * releaseCoeff;
            }
            double out = delayed * gain;
            if (out > ceiling) {
                out = ceiling;
            } else if (out < -ceiling) {
                out = -ceiling;
            }
            blockMin = Math.min(blockMin, gain);
            samples[i] = (float) out;
        }
        minGainInBlock = blockMin;
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(delay, 0f);
        java.util.Arrays.fill(requiredGain, 1.0);
        index = 0;
        gain = 1.0;
        minGainInBlock = 1.0;
    }
}
