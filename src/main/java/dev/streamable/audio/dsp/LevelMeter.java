package dev.streamable.audio.dsp;

/**
 * Calibrated level measurement for the microphone meters.
 *
 * <p>Reports, in dBFS: the instantaneous peak of the last block, an RMS
 * average over ~300 ms (the loudness-like bar), a peak hold that decays after
 * 1.5 s, and a clip indicator that latches for 2 s whenever a sample reaches
 * {@link #CLIP_LEVEL}. Single writer (the DSP thread); readers take the
 * immutable {@link Reading}.</p>
 */
public final class LevelMeter {

    /** Samples at or above this magnitude count as clipped (-0.1 dBFS). */
    public static final double CLIP_LEVEL = 0.9886;

    /** A consistent snapshot for the UI. */
    public record Reading(double peakDb, double rmsDb, double peakHoldDb, boolean clipping, long clippedSamples) {
        public static final Reading SILENT = new Reading(Db.FLOOR_DB, Db.FLOOR_DB, Db.FLOOR_DB, false, 0);
    }

    private final double sampleRate;
    private final double rmsCoeff;
    private double meanSquare;
    private double holdDb = Db.FLOOR_DB;
    private long holdUntilSamples;
    private long clipUntilSamples;
    private long clippedSamples;
    private long position;
    private volatile Reading reading = Reading.SILENT;

    public LevelMeter(double sampleRate) {
        this.sampleRate = sampleRate;
        this.rmsCoeff = Db.coefficient(300, sampleRate);
    }

    public void process(float[] samples, int offset, int length) {
        double peak = 0;
        for (int i = offset; i < offset + length; i++) {
            double x = samples[i];
            double magnitude = Math.abs(x);
            peak = Math.max(peak, magnitude);
            meanSquare = x * x + (meanSquare - x * x) * rmsCoeff;
            if (magnitude >= CLIP_LEVEL) {
                clippedSamples++;
                clipUntilSamples = position + (long) (2 * sampleRate);
            }
        }
        position += length;
        double peakDb = Db.fromLinear(peak);
        if (peakDb >= holdDb || position > holdUntilSamples) {
            holdDb = peakDb >= holdDb ? peakDb : Math.max(peakDb, holdDb - 20.0 * length / sampleRate);
            if (peakDb >= holdDb) {
                holdUntilSamples = position + (long) (1.5 * sampleRate);
            }
        }
        reading = new Reading(peakDb, Db.fromPower(meanSquare), holdDb, position < clipUntilSamples, clippedSamples);
    }

    public Reading reading() {
        return reading;
    }

    public void reset() {
        meanSquare = 0;
        holdDb = Db.FLOOR_DB;
        holdUntilSamples = 0;
        clipUntilSamples = 0;
        clippedSamples = 0;
        position = 0;
        reading = Reading.SILENT;
    }
}
