package dev.streamable.audio.dsp;

import dev.streamable.config.MicrophoneSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * A parametric equaliser: shelves and bells built from cookbook biquads.
 *
 * <p>{@link #responseDb(double)} evaluates the exact cascaded response, which is
 * what the EQ graph plots.</p>
 */
public final class EqualizerStage implements AudioStage {

    public static final int MAX_BANDS = 8;

    private final double sampleRate;
    private final Biquad[] filters = new Biquad[MAX_BANDS];
    private final BiquadDesign.Coefficients[] coefficients = new BiquadDesign.Coefficients[MAX_BANDS];
    private int activeBands;
    private boolean enabled;

    public EqualizerStage(double sampleRate) {
        this.sampleRate = sampleRate;
        for (int i = 0; i < MAX_BANDS; i++) {
            filters[i] = new Biquad();
            coefficients[i] = BiquadDesign.Coefficients.IDENTITY;
        }
    }

    @Override
    public String id() {
        return "eq";
    }

    /** Coefficients for one band; shared with the response graph. */
    public static BiquadDesign.Coefficients design(MicrophoneSettings.EqBand band, double sampleRate) {
        return switch (band.type) {
            case LOW_SHELF -> BiquadDesign.lowShelf(band.frequencyHz, band.gainDb, band.q, sampleRate);
            case HIGH_SHELF -> BiquadDesign.highShelf(band.frequencyHz, band.gainDb, band.q, sampleRate);
            case LOW_PASS -> BiquadDesign.lowPass(band.frequencyHz, band.q, sampleRate);
            case BELL -> BiquadDesign.peaking(band.frequencyHz, band.gainDb, band.q, sampleRate);
        };
    }

    @Override
    public void configure(MicrophoneSettings settings) {
        enabled = settings.eq.enabled;
        List<MicrophoneSettings.EqBand> bands = new ArrayList<>(settings.eq.bands);
        int count = 0;
        for (MicrophoneSettings.EqBand band : bands) {
            if (count >= MAX_BANDS) {
                break;
            }
            if (!band.enabled || (band.type != MicrophoneSettings.BandType.LOW_PASS && Math.abs(band.gainDb) < 0.01)) {
                continue;   // a 0 dB band is an identity filter: skip its cost
            }
            BiquadDesign.Coefficients c = design(band, sampleRate);
            if (!c.equals(coefficients[count])) {
                coefficients[count] = c;
                filters[count].set(c);
            }
            count++;
        }
        activeBands = count;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void process(float[] samples, int offset, int length) {
        for (int i = 0; i < activeBands; i++) {
            filters[i].process(samples, offset, length);
        }
    }

    /** The cascaded magnitude response of the given settings at a frequency. */
    public static double responseDb(MicrophoneSettings.Equalizer eq, double frequencyHz, double sampleRate) {
        if (!eq.enabled) {
            return 0;
        }
        double total = 0;
        for (MicrophoneSettings.EqBand band : eq.bands) {
            if (band.enabled) {
                total += design(band, sampleRate).magnitudeDb(frequencyHz, sampleRate);
            }
        }
        return total;
    }

    @Override
    public void reset() {
        for (Biquad filter : filters) {
            filter.reset();
        }
    }
}
