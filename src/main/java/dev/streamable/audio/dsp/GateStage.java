package dev.streamable.audio.dsp;

import dev.streamable.config.MicrophoneSettings;

/**
 * A smooth noise gate / downward expander.
 *
 * <p>Designed not to sound like a gate:</p>
 * <ul>
 *   <li><b>Downward expansion, limited range.</b> Below the threshold the
 *       level falls by {@code ratio} (3:1 by default) and never by more than
 *       {@code range} dB, so room tone is lowered, not switched off.</li>
 *   <li><b>Look-ahead.</b> The detector sees the signal a few milliseconds
 *       before it is output, so the gate is already open when a word begins -
 *       consonants are never clipped.</li>
 *   <li><b>Hold and slow release.</b> Sentence endings and trailing breaths
 *       decay naturally instead of being chopped.</li>
 *   <li><b>Hysteresis.</b> It opens at the threshold but closes only below
 *       {@code threshold - hysteresis}, which stops chattering on levels that
 *       hover near the threshold.</li>
 * </ul>
 */
public final class GateStage implements AudioStage {

    private static final int MAX_LOOKAHEAD = 1024;

    private final double sampleRate;
    private final float[] delay = new float[MAX_LOOKAHEAD];
    private int delayIndex;
    private int lookahead;
    private boolean enabled;
    private double openDb;
    private double closeDb;
    private double rangeDb;
    private double ratio;
    private double attackCoeff;
    private double releaseCoeff;
    private int holdSamples;

    private double envelope;          // peak envelope of the detector, linear
    private double gainDb;            // smoothed applied gain, <= 0
    private boolean open;
    private int holdCounter;
    private long openings;            // chatter diagnostics

    public GateStage(double sampleRate) {
        this.sampleRate = sampleRate;
    }

    @Override
    public String id() {
        return "gate";
    }

    @Override
    public void configure(MicrophoneSettings settings) {
        MicrophoneSettings.Gate gate = settings.gate;
        enabled = gate.enabled;
        openDb = gate.thresholdDb;
        closeDb = gate.thresholdDb - gate.hysteresisDb;
        rangeDb = gate.rangeDb;
        ratio = gate.ratio;
        attackCoeff = Db.coefficient(gate.attackMs, sampleRate);
        releaseCoeff = Db.coefficient(gate.releaseMs, sampleRate);
        holdSamples = (int) (gate.holdMs * 0.001 * sampleRate);
        int newLookahead = Math.min(MAX_LOOKAHEAD - 1, (int) Math.round(gate.lookaheadMs * 0.001 * sampleRate));
        if (newLookahead != lookahead) {
            lookahead = newLookahead;
            java.util.Arrays.fill(delay, 0f);
            delayIndex = 0;
        }
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
        return -gainDb;
    }

    public boolean isOpen() {
        return open;
    }

    /** How many times the gate opened since reset; many per second means chatter. */
    public long openings() {
        return openings;
    }

    /** Static curve: target gain (dB, <= 0) for a detector level. */
    double targetGainDb(double levelDb, boolean isOpen) {
        if (isOpen) {
            return 0;
        }
        double below = openDb - levelDb;
        if (below <= 0) {
            return 0;
        }
        return -Math.min(rangeDb, below * (ratio - 1));
    }

    @Override
    public void process(float[] samples, int offset, int length) {
        // Envelope follower: instant attack, 20 ms decay, on the undelayed
        // signal. Hold and release, not the detector, decide how long it stays open.
        double decay = Db.coefficient(20, sampleRate);
        for (int i = offset; i < offset + length; i++) {
            float input = samples[i];
            double magnitude = Math.abs(input);
            envelope = magnitude > envelope ? magnitude : envelope * decay + magnitude * (1 - decay);
            double levelDb = Db.fromLinear(envelope);

            if (levelDb >= openDb) {
                if (!open) {
                    openings++;
                }
                open = true;
                holdCounter = holdSamples;
            } else if (open && levelDb < closeDb) {
                if (holdCounter > 0) {
                    holdCounter--;
                } else {
                    open = false;
                }
            }

            double target = targetGainDb(levelDb, open);
            // Opening uses the attack time, closing the release time.
            double coeff = target > gainDb ? attackCoeff : releaseCoeff;
            gainDb = target + (gainDb - target) * coeff;

            float delayed;
            if (lookahead > 0) {
                delayed = delay[delayIndex];
                delay[delayIndex] = input;
                delayIndex = (delayIndex + 1) % lookahead;
            } else {
                delayed = input;
            }
            samples[i] = (float) (delayed * Db.toLinearFast(gainDb));
        }
    }

    @Override
    public void reset() {
        envelope = 0;
        gainDb = 0;
        open = false;
        holdCounter = 0;
        java.util.Arrays.fill(delay, 0f);
        delayIndex = 0;
        openings = 0;
    }
}
