package dev.streamable.audio.dsp;

/**
 * A second-order IIR section in transposed direct form II, with double
 * precision state.
 *
 * <p>TDF-II keeps its two state variables small and well-conditioned, which
 * matters for low-frequency filters (an 80 Hz high-pass at 48 kHz) where direct
 * form I in single precision audibly misbehaves. Coefficients are normalised
 * ({@code a0 = 1}).</p>
 */
public final class Biquad {

    private double b0 = 1;
    private double b1;
    private double b2;
    private double a1;
    private double a2;
    private double z1;
    private double z2;

    public void set(BiquadDesign.Coefficients c) {
        b0 = c.b0();
        b1 = c.b1();
        b2 = c.b2();
        a1 = c.a1();
        a2 = c.a2();
    }

    /** Pass-through. */
    public void setIdentity() {
        b0 = 1;
        b1 = 0;
        b2 = 0;
        a1 = 0;
        a2 = 0;
    }

    public double process(double x) {
        double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }

    public void process(float[] buffer, int offset, int length) {
        double lz1 = z1;
        double lz2 = z2;
        for (int i = offset; i < offset + length; i++) {
            double x = buffer[i];
            double y = b0 * x + lz1;
            lz1 = b1 * x - a1 * y + lz2;
            lz2 = b2 * x - a2 * y;
            buffer[i] = (float) y;
        }
        // Flush denormals: long silences otherwise make the state subnormal,
        // which costs tens of times more CPU per sample on x86.
        z1 = Math.abs(lz1) < 1e-25 ? 0 : lz1;
        z2 = Math.abs(lz2) < 1e-25 ? 0 : lz2;
    }

    public void reset() {
        z1 = 0;
        z2 = 0;
    }
}
