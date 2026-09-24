package dev.streamable.audio.mic;

import dev.streamable.audio.dsp.Db;
import dev.streamable.audio.dsp.LevelMeter;
import dev.streamable.config.MicrophoneSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * "Calibrate Microphone": measures the room, normal speech and (optionally)
 * loud speech on the <em>raw</em> input, then recommends a digital input gain
 * and gate threshold.
 *
 * <p>Measurements are taken before any Stream-able gain, which is what makes
 * the upstream-clipping check meaningful: if the signal already reaches full
 * scale here, the microphone or interface is clipping and no digital gain can
 * undo it - the recommendation says so rather than pretending otherwise.</p>
 */
public final class MicrophoneCalibration {

    public enum Step {
        IDLE(""),
        ROOM("Stay quiet for a moment while the room noise is measured."),
        NORMAL("Now speak normally, as you would on stream."),
        LOUD("Optional: speak loudly or laugh, as your loudest moments would."),
        DONE("Calibration complete.");

        private final String instruction;

        Step(String instruction) {
            this.instruction = instruction;
        }

        public String instruction() {
            return instruction;
        }
    }

    /** Recommendation shown to the user; applied only when they choose to. */
    public record Result(double noiseFloorDb, double speechRmsDb, double speechPeakDb, double loudPeakDb,
                         boolean upstreamClipping, double recommendedInputGainDb, double recommendedGateDb,
                         List<String> advice) {
    }

    /** Target long-term speech level after input gain: headroom for the chain, still clearly audible. */
    static final double TARGET_SPEECH_RMS_DB = -20;
    /** Loudest peaks should stay below this after gain; the limiter handles the rest. */
    static final double MAX_PEAK_AFTER_GAIN_DB = -4;

    private static final int RATE = 48_000;
    private static final double[] STEP_SECONDS = {0, 3, 6, 4, 0};

    private final MicrophoneProcessor processor;
    private volatile Step step = Step.IDLE;
    private volatile long stepSamples;
    private volatile Result result;
    private boolean includeLoud = true;
    private final double[] power = new double[3];
    private final long[] count = new long[3];
    private final double[] peak = new double[3];
    private final long[] clipped = new long[3];
    // Room noise uses the quietest 50 % of 50 ms windows, which ignores a cough or a keypress.
    private final List<Double> roomWindows = new ArrayList<>();
    private double windowPower;
    private int windowCount;
    private final BlockListener tap = this::onRaw;

    public MicrophoneCalibration(MicrophoneProcessor processor) {
        this.processor = processor;
    }

    public Step step() {
        return step;
    }

    /** Progress through the current step, 0..1. */
    public double stepProgress() {
        double seconds = STEP_SECONDS[step.ordinal()];
        return seconds <= 0 ? 1 : Math.min(1, stepSamples / (seconds * RATE));
    }

    public Result result() {
        return result;
    }

    public synchronized void start(boolean withLoudStep) {
        includeLoud = withLoudStep;
        result = null;
        java.util.Arrays.fill(power, 0);
        java.util.Arrays.fill(count, 0);
        java.util.Arrays.fill(peak, 0);
        java.util.Arrays.fill(clipped, 0);
        roomWindows.clear();
        windowPower = 0;
        windowCount = 0;
        stepSamples = 0;
        step = Step.ROOM;
        processor.addRawListener(tap);
    }

    public synchronized void cancel() {
        processor.removeRawListener(tap);
        step = Step.IDLE;
    }

    /** Skips the optional loud step. */
    public synchronized void skipLoud() {
        if (step == Step.LOUD) {
            finish();
        }
    }

    private synchronized void onRaw(float[] samples, int length) {
        int index = switch (step) {
            case ROOM -> 0;
            case NORMAL -> 1;
            case LOUD -> 2;
            default -> -1;
        };
        if (index < 0) {
            return;
        }
        for (int i = 0; i < length; i++) {
            double x = samples[i];
            double magnitude = Math.abs(x);
            power[index] += x * x;
            peak[index] = Math.max(peak[index], magnitude);
            if (magnitude >= LevelMeter.CLIP_LEVEL) {
                clipped[index]++;
            }
            if (index == 0) {
                windowPower += x * x;
                if (++windowCount == RATE / 20) {
                    roomWindows.add(windowPower / windowCount);
                    windowPower = 0;
                    windowCount = 0;
                }
            }
        }
        count[index] += length;
        stepSamples += length;
        if (stepSamples >= STEP_SECONDS[step.ordinal()] * RATE) {
            stepSamples = 0;
            if (step == Step.ROOM) {
                step = Step.NORMAL;
            } else if (step == Step.NORMAL) {
                if (includeLoud) {
                    step = Step.LOUD;
                } else {
                    finish();
                }
            } else {
                finish();
            }
        }
    }

    private void finish() {
        processor.removeRawListener(tap);
        result = analyse(roomFloorPower(), power[1] / Math.max(1, count[1]), peak[1],
                count[2] > 0 ? peak[2] : peak[1], clipped[1] + clipped[2], count[1] + count[2]);
        step = Step.DONE;
    }

    private double roomFloorPower() {
        if (roomWindows.isEmpty()) {
            return power[0] / Math.max(1, count[0]);
        }
        List<Double> sorted = new ArrayList<>(roomWindows);
        sorted.sort(Double::compare);
        List<Double> quiet = sorted.subList(0, Math.max(1, sorted.size() / 2));
        return quiet.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** Pure analysis, separated for testing. */
    static Result analyse(double roomPower, double speechPower, double speechPeak, double loudPeak,
                          long clippedSamples, long speechSamples) {
        double noiseDb = Db.fromPower(roomPower);
        double speechDb = Db.fromPower(speechPower);
        double speechPeakDb = Db.fromLinear(speechPeak);
        double loudPeakDb = Db.fromLinear(loudPeak);
        boolean clipping = speechSamples > 0 && clippedSamples > Math.max(3, speechSamples / 5000);
        List<String> advice = new ArrayList<>();

        double gain = TARGET_SPEECH_RMS_DB - speechDb;
        // Never recommend so much gain that loud moments would be driven into the limiter hard.
        gain = Math.min(gain, MAX_PEAK_AFTER_GAIN_DB - loudPeakDb);
        gain = Math.clamp(gain, -12, 24);
        if (speechDb < -60) {
            advice.add("No speech was detected. Check that the right microphone is selected and not muted.");
            gain = 0;
        }
        if (clipping) {
            advice.add("Input is already clipping before Stream-able processing. Lower the microphone/interface gain.");
            gain = Math.min(gain, 0);
        } else if (loudPeakDb > -1) {
            advice.add("Your loudest moments come very close to clipping at the source. Lowering the "
                    + "microphone or interface gain slightly would give more headroom.");
        }
        if (gain > 18) {
            advice.add("The microphone is very quiet. Raising its gain in the system or on the interface "
                    + "gives a cleaner signal than large digital gain.");
        }
        double snr = speechDb - noiseDb;
        if (snr < 15 && speechDb > -60) {
            advice.add(String.format(java.util.Locale.ROOT,
                    "Background noise is only %.0f dB below your voice. AI noise cancellation (Balanced or "
                            + "Strong) is recommended.", snr));
        }
        // Gate: comfortably above the room, comfortably below speech.
        double gate = Math.min(noiseDb + gain + 8, speechDb + gain - 15);
        gate = Math.clamp(gate, -80, -25);
        if (speechDb + gain - (noiseDb + gain) < 20) {
            advice.add("The room noise is close to your speaking level, so the gate is set gently to avoid "
                    + "cutting off quiet words.");
        }
        if (advice.isEmpty()) {
            advice.add("Levels look good.");
        }
        return new Result(noiseDb, speechDb, speechPeakDb, loudPeakDb, clipping, gain, gate, List.copyOf(advice));
    }

    /** Applies the recommendation to the settings. */
    public static void apply(Result result, MicrophoneSettings settings) {
        settings.inputGainDb = Math.round(result.recommendedInputGainDb() * 2) / 2.0;
        settings.gate.thresholdDb = Math.round(result.recommendedGateDb());
        settings.touch();
    }
}
