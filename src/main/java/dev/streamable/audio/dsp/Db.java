package dev.streamable.audio.dsp;

/** Decibel conversions. Levels are relative to digital full scale (dBFS). */
public final class Db {

    /** Treated as silence; avoids -Infinity in envelopes and meters. */
    public static final double FLOOR_DB = -120.0;

    private Db() {
    }

    public static double toLinear(double db) {
        return Math.pow(10.0, db / 20.0);
    }

    private static final double DB_TO_NEPER = Math.log(10.0) / 20.0;

    /** Same as {@link #toLinear} via {@code exp}, which is several times cheaper than {@code pow}. */
    public static double toLinearFast(double db) {
        return Math.exp(db * DB_TO_NEPER);
    }

    public static double fromLinear(double linear) {
        return linear <= 1e-6 ? FLOOR_DB : 20.0 * Math.log10(linear);
    }

    /** dB of a mean-square power value. */
    public static double fromPower(double power) {
        return power <= 1e-12 ? FLOOR_DB : 10.0 * Math.log10(power);
    }

    /** One-pole smoothing coefficient for a time constant in milliseconds. */
    public static double coefficient(double timeMs, double sampleRate) {
        if (timeMs <= 0) {
            return 0.0;
        }
        return Math.exp(-1.0 / (timeMs * 0.001 * sampleRate));
    }
}
