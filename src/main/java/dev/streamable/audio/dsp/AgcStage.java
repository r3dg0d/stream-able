package dev.streamable.audio.dsp;

import dev.streamable.config.MicrophoneSettings;

/**
 * Slow automatic gain control - not a compressor.
 *
 * <p>It measures the long-term level of <em>speech</em> (400 ms windows that
 * clearly contain voice) and moves a single gain toward the target at a few dB
 * per second. Windows that look like silence or room tone freeze the gain, so it
 * never "chases" pauses by turning the noise up. Short-term dynamics are left
 * to the compressor and limiter that follow it.</p>
 */
public final class AgcStage implements AudioStage {

    private final double sampleRate;
    private final int windowSamples;
    private boolean enabled;
    private double targetDb;
    private double maxGainDb;
    private double speedDbPerSecond;
    private double gainDb;
    private double appliedGainDb;
    private double windowPower;
    private int windowCount;
    private double noiseFloorDb = -70;
    private final double smoothing;

    public AgcStage(double sampleRate) {
        this.sampleRate = sampleRate;
        this.windowSamples = (int) (0.4 * sampleRate);
        this.smoothing = Db.coefficient(50, sampleRate);
    }

    @Override
    public String id() {
        return "agc";
    }

    @Override
    public void configure(MicrophoneSettings settings) {
        MicrophoneSettings.AutomaticGain agc = settings.agc;
        enabled = agc.enabled;
        targetDb = agc.targetDb;
        maxGainDb = agc.maxGainDb;
        speedDbPerSecond = agc.speedDbPerSecond;
        gainDb = Math.clamp(gainDb, -maxGainDb, maxGainDb);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** Current AGC gain in dB (may be negative when turning a loud voice down). */
    public double currentGainDb() {
        return gainDb;
    }

    @Override
    public double gainReductionDb() {
        return -gainDb;
    }

    @Override
    public void process(float[] samples, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            double x = samples[i];
            windowPower += x * x;
            if (++windowCount >= windowSamples) {
                evaluateWindow(windowPower / windowCount);
                windowPower = 0;
                windowCount = 0;
            }
            appliedGainDb = gainDb + (appliedGainDb - gainDb) * smoothing;
            samples[i] = (float) (x * Db.toLinearFast(appliedGainDb));
        }
    }

    /** Called once per 400 ms window with its mean power (before AGC gain). */
    void evaluateWindow(double meanPower) {
        double levelDb = Db.fromPower(meanPower);
        // Track the room's noise floor slowly, and quickly downward.
        noiseFloorDb = levelDb < noiseFloorDb ? levelDb : noiseFloorDb + 0.05;
        boolean speech = levelDb > noiseFloorDb + 12 && levelDb > -60;
        if (!speech) {
            return;   // freeze: never raise the gain because the room went quiet
        }
        double error = targetDb - (levelDb + gainDb);
        double step = speedDbPerSecond * windowSamples / sampleRate;
        gainDb = Math.clamp(gainDb + Math.clamp(error, -step, step), -maxGainDb, maxGainDb);
    }

    @Override
    public void reset() {
        gainDb = 0;
        appliedGainDb = 0;
        windowPower = 0;
        windowCount = 0;
        noiseFloorDb = -70;
    }
}
