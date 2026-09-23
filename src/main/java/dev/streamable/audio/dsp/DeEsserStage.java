package dev.streamable.audio.dsp;

import dev.streamable.config.MicrophoneSettings;

/**
 * A dynamic high-shelf de-esser.
 *
 * <p>Detection listens to a band-pass around the sibilance frequency and
 * compares it with the broadband level, so loud but non-sibilant speech does
 * not trigger it - that relative detection is what prevents the lisping sound
 * of over-eager de-essers. The reduction is applied by a high shelf starting
 * just below the sibilance band whose gain follows the detector. At 0 dB a
 * cookbook shelf is exactly the identity, so the de-esser is transparent until
 * it acts; coefficients are recomputed every {@value #UPDATE_INTERVAL} samples
 * from the smoothed reduction, which keeps the filter change inaudible.</p>
 */
public final class DeEsserStage implements AudioStage {

    /** Centre of the "Auto" detection band: the middle of typical sibilance. */
    public static final double AUTO_FREQUENCY_HZ = 6500;
    static final int UPDATE_INTERVAL = 32;

    private final double sampleRate;
    private final Biquad shelf = new Biquad();
    private final Biquad detector = new Biquad();
    private boolean enabled;
    private double thresholdDb;
    private double maxReductionDb;
    private double shelfFrequency;
    private double configuredFrequency = -1;
    private final double attack;
    private final double release;
    private final double levelSmoothing;
    private double sibilantPower;
    private double broadbandPower;
    private double reductionDb;
    private double appliedShelfDb;
    private int sinceUpdate;

    public DeEsserStage(double sampleRate) {
        this.sampleRate = sampleRate;
        this.attack = Db.coefficient(1.0, sampleRate);
        this.release = Db.coefficient(80, sampleRate);
        this.levelSmoothing = Db.coefficient(5, sampleRate);
    }

    @Override
    public String id() {
        return "deesser";
    }

    @Override
    public void configure(MicrophoneSettings settings) {
        MicrophoneSettings.DeEsser d = settings.deEsser;
        enabled = d.enabled;
        thresholdDb = d.thresholdDb;
        maxReductionDb = d.amountDb;
        double frequency = d.auto ? AUTO_FREQUENCY_HZ : d.frequencyHz;
        if (frequency != configuredFrequency) {
            configuredFrequency = frequency;
            shelfFrequency = frequency * 0.75;
            detector.set(BiquadDesign.bandPass(frequency, d.auto ? 1.0 : 2.0, sampleRate));
            appliedShelfDb = Double.NaN;   // force a coefficient update
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public double gainReductionDb() {
        return reductionDb;
    }

    @Override
    public void process(float[] samples, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            double x = samples[i];
            double band = detector.process(x);
            sibilantPower = band * band + (sibilantPower - band * band) * levelSmoothing;
            broadbandPower = x * x + (broadbandPower - x * x) * levelSmoothing;

            double sibilantDb = Db.fromPower(sibilantPower);
            double relativeDb = sibilantDb - Db.fromPower(broadbandPower);
            // Act only when the band is above the threshold AND dominates the
            // spectrum; a relative level below -9 dB means ordinary voiced speech.
            double over = Math.min(sibilantDb - thresholdDb, relativeDb + 9);
            double target = over > 0 ? Math.min(maxReductionDb, over) : 0;
            double coeff = target > reductionDb ? attack : release;
            reductionDb = target + (reductionDb - target) * coeff;

            if (++sinceUpdate >= UPDATE_INTERVAL || Double.isNaN(appliedShelfDb)) {
                sinceUpdate = 0;
                double shelfDb = -Math.round(reductionDb * 20) / 20.0;   // 0.05 dB steps
                if (shelfDb != appliedShelfDb) {
                    appliedShelfDb = shelfDb;
                    shelf.set(BiquadDesign.highShelf(shelfFrequency, shelfDb, BiquadDesign.BUTTERWORTH_2_Q, sampleRate));
                }
            }
            samples[i] = (float) shelf.process(x);
        }
    }

    @Override
    public void reset() {
        shelf.reset();
        detector.reset();
        sibilantPower = 0;
        broadbandPower = 0;
        reductionDb = 0;
        appliedShelfDb = Double.NaN;
    }
}
