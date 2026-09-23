package dev.streamable.audio.dsp;

/**
 * Filter designs from Robert Bristow-Johnson's "Audio EQ Cookbook".
 *
 * <p>Besides coefficients, each design can report its exact magnitude response
 * at any frequency, which is what the EQ graph draws - so the curve on screen
 * is the filter the audio actually passes through, not an approximation.</p>
 */
public final class BiquadDesign {

    /** Normalised coefficients ({@code a0 = 1}). */
    public record Coefficients(double b0, double b1, double b2, double a1, double a2) {

        public static final Coefficients IDENTITY = new Coefficients(1, 0, 0, 0, 0);

        /** |H(e^jw)| in dB at {@code frequencyHz}. */
        public double magnitudeDb(double frequencyHz, double sampleRate) {
            double w = 2 * Math.PI * frequencyHz / sampleRate;
            double cos1 = Math.cos(w);
            double sin1 = Math.sin(w);
            double cos2 = Math.cos(2 * w);
            double sin2 = Math.sin(2 * w);
            double numRe = b0 + b1 * cos1 + b2 * cos2;
            double numIm = -(b1 * sin1 + b2 * sin2);
            double denRe = 1 + a1 * cos1 + a2 * cos2;
            double denIm = -(a1 * sin1 + a2 * sin2);
            double num = numRe * numRe + numIm * numIm;
            double den = denRe * denRe + denIm * denIm;
            return 10 * Math.log10(Math.max(num, 1e-30) / Math.max(den, 1e-30));
        }

        /** Whether both poles are inside the unit circle. */
        public boolean isStable() {
            return Math.abs(a2) < 1 && Math.abs(a1) < 1 + a2;
        }
    }

    private BiquadDesign() {
    }

    private static double omega(double frequency, double sampleRate) {
        double nyquistSafe = Math.clamp(frequency, 1.0, sampleRate * 0.49);
        return 2 * Math.PI * nyquistSafe / sampleRate;
    }

    private static Coefficients normalise(double b0, double b1, double b2, double a0, double a1, double a2) {
        return new Coefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
    }

    public static Coefficients highPass(double frequency, double q, double sampleRate) {
        double w = omega(frequency, sampleRate);
        double cos = Math.cos(w);
        double alpha = Math.sin(w) / (2 * q);
        return normalise((1 + cos) / 2, -(1 + cos), (1 + cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
    }

    public static Coefficients lowPass(double frequency, double q, double sampleRate) {
        double w = omega(frequency, sampleRate);
        double cos = Math.cos(w);
        double alpha = Math.sin(w) / (2 * q);
        return normalise((1 - cos) / 2, 1 - cos, (1 - cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
    }

    /** Constant 0 dB peak gain band-pass. */
    public static Coefficients bandPass(double frequency, double q, double sampleRate) {
        double w = omega(frequency, sampleRate);
        double cos = Math.cos(w);
        double alpha = Math.sin(w) / (2 * q);
        return normalise(alpha, 0, -alpha, 1 + alpha, -2 * cos, 1 - alpha);
    }

    public static Coefficients peaking(double frequency, double gainDb, double q, double sampleRate) {
        double a = Math.pow(10, gainDb / 40);
        double w = omega(frequency, sampleRate);
        double cos = Math.cos(w);
        double alpha = Math.sin(w) / (2 * q);
        return normalise(1 + alpha * a, -2 * cos, 1 - alpha * a, 1 + alpha / a, -2 * cos, 1 - alpha / a);
    }

    /** Shelf with slope expressed through Q (0.707 = Butterworth-like). */
    public static Coefficients lowShelf(double frequency, double gainDb, double q, double sampleRate) {
        double a = Math.pow(10, gainDb / 40);
        double w = omega(frequency, sampleRate);
        double cos = Math.cos(w);
        double alpha = Math.sin(w) / (2 * q);
        double twoSqrtAAlpha = 2 * Math.sqrt(a) * alpha;
        return normalise(
                a * ((a + 1) - (a - 1) * cos + twoSqrtAAlpha),
                2 * a * ((a - 1) - (a + 1) * cos),
                a * ((a + 1) - (a - 1) * cos - twoSqrtAAlpha),
                (a + 1) + (a - 1) * cos + twoSqrtAAlpha,
                -2 * ((a - 1) + (a + 1) * cos),
                (a + 1) + (a - 1) * cos - twoSqrtAAlpha);
    }

    public static Coefficients highShelf(double frequency, double gainDb, double q, double sampleRate) {
        double a = Math.pow(10, gainDb / 40);
        double w = omega(frequency, sampleRate);
        double cos = Math.cos(w);
        double alpha = Math.sin(w) / (2 * q);
        double twoSqrtAAlpha = 2 * Math.sqrt(a) * alpha;
        return normalise(
                a * ((a + 1) + (a - 1) * cos + twoSqrtAAlpha),
                -2 * a * ((a - 1) + (a + 1) * cos),
                a * ((a + 1) + (a - 1) * cos - twoSqrtAAlpha),
                (a + 1) - (a - 1) * cos + twoSqrtAAlpha,
                2 * ((a - 1) - (a + 1) * cos),
                (a + 1) - (a - 1) * cos - twoSqrtAAlpha);
    }

    /** Q values for cascading two sections into a 4th-order Butterworth. */
    public static final double[] BUTTERWORTH_4_Q = {0.54119610, 1.30656296};
    public static final double BUTTERWORTH_2_Q = Math.sqrt(0.5);
}
