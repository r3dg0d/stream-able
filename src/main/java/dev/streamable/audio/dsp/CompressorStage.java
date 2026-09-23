package dev.streamable.audio.dsp;

import dev.streamable.config.MicrophoneSettings;

/**
 * A feed-forward, soft-knee broadcast compressor.
 *
 * <p>The detector is an RMS-like power envelope with a short window, which
 * reacts to perceived loudness rather than to individual waveform peaks - that
 * alone removes most of the "pumping" peak compressors produce on speech. Gain
 * computation happens in the log domain and the gain is smoothed with separate
 * attack and release times, so the envelope is stable at any ratio.</p>
 */
public final class CompressorStage implements AudioStage {

    private final double sampleRate;
    private final double detectorCoeff;
    private boolean enabled;
    private double thresholdDb;
    private double ratio;
    private double kneeDb;
    private double makeupDb;
    private double attackCoeff;
    private double releaseCoeff;
    private double power;
    private double gainReductionDb;

    public CompressorStage(double sampleRate) {
        this.sampleRate = sampleRate;
        this.detectorCoeff = Db.coefficient(10, sampleRate);
    }

    @Override
    public String id() {
        return "compressor";
    }

    @Override
    public void configure(MicrophoneSettings settings) {
        MicrophoneSettings.Compressor c = settings.compressor;
        enabled = c.enabled;
        thresholdDb = c.thresholdDb;
        ratio = c.ratio;
        kneeDb = c.kneeDb;
        makeupDb = c.autoMakeup ? autoMakeupDb(c.thresholdDb, c.ratio) : c.makeupDb;
        attackCoeff = Db.coefficient(c.attackMs, sampleRate);
        releaseCoeff = Db.coefficient(c.releaseMs, sampleRate);
    }

    /**
     * Automatic makeup: half the reduction a signal at 0 dBFS would receive.
     * Restores perceived level without driving the limiter into constant work.
     */
    public static double autoMakeupDb(double thresholdDb, double ratio) {
        return Math.max(0, -thresholdDb * (1 - 1 / ratio) * 0.5);
    }

    /** Static gain curve (dB of reduction, >= 0) with a quadratic soft knee. */
    public static double staticReductionDb(double levelDb, double thresholdDb, double ratio, double kneeDb) {
        double over = levelDb - thresholdDb;
        double slope = 1 - 1 / ratio;
        if (kneeDb > 0 && Math.abs(over) <= kneeDb / 2) {
            double x = over + kneeDb / 2;
            return slope * x * x / (2 * kneeDb);
        }
        return over > 0 ? slope * over : 0;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public double gainReductionDb() {
        return gainReductionDb;
    }

    @Override
    public void process(float[] samples, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            double x = samples[i];
            power = x * x + (power - x * x) * detectorCoeff;
            // +3 dB converts a mean-square level of a sine to its peak level scale.
            double levelDb = Db.fromPower(power) + 3.0103;
            double target = staticReductionDb(levelDb, thresholdDb, ratio, kneeDb);
            double coeff = target > gainReductionDb ? attackCoeff : releaseCoeff;
            gainReductionDb = target + (gainReductionDb - target) * coeff;
            samples[i] = (float) (x * Db.toLinearFast(makeupDb - gainReductionDb));
        }
    }

    @Override
    public void reset() {
        power = 0;
        gainReductionDb = 0;
    }
}
