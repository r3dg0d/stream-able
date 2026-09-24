package dev.streamable.audio.mic;

/**
 * "Test Microphone": records a few seconds of the raw and the processed signal
 * side by side, then plays either back for A/B comparison.
 *
 * <p>Entirely local - the clips live in memory, are never written to the
 * recording, never streamed and never uploaded. It does not require a
 * recording or stream to be running.</p>
 */
public final class MicrophoneTest {

    public enum State { IDLE, RECORDING, READY, PLAYING_RAW, PLAYING_PROCESSED }

    public static final int MAX_SECONDS = 15;
    private static final int RATE = 48_000;

    private final MicrophoneProcessor processor;
    private final AudioPlayback playback = new AudioPlayback();
    private final float[] raw = new float[RATE * MAX_SECONDS];
    private final float[] processed = new float[RATE * MAX_SECONDS];
    private volatile int rawLength;
    private volatile int processedLength;
    private volatile int targetLength;
    private volatile State state = State.IDLE;
    private final BlockListener rawTap = this::onRaw;
    private final BlockListener processedTap = this::onProcessed;

    public MicrophoneTest(MicrophoneProcessor processor) {
        this.processor = processor;
    }

    public State state() {
        return state;
    }

    public double recordedSeconds() {
        return processedLength / (double) RATE;
    }

    public double targetSeconds() {
        return targetLength / (double) RATE;
    }

    public double playbackProgress() {
        return playback.progress();
    }

    /** Starts recording both signals for {@code seconds} (5-15). */
    public synchronized void record(int seconds) {
        stopPlayback();
        rawLength = 0;
        processedLength = 0;
        targetLength = RATE * Math.clamp(seconds, 3, MAX_SECONDS);
        state = State.RECORDING;
        processor.addRawListener(rawTap);
        processor.addProcessedListener(processedTap);
    }

    private void onRaw(float[] samples, int length) {
        int n = Math.min(length, targetLength - rawLength);
        if (n > 0) {
            System.arraycopy(samples, 0, raw, rawLength, n);
            rawLength += n;
        }
    }

    private void onProcessed(float[] samples, int length) {
        int n = Math.min(length, targetLength - processedLength);
        if (n > 0) {
            System.arraycopy(samples, 0, processed, processedLength, n);
            processedLength += n;
        }
        if (processedLength >= targetLength) {
            finishRecording();
        }
    }

    private synchronized void finishRecording() {
        if (state != State.RECORDING) {
            return;
        }
        processor.removeRawListener(rawTap);
        processor.removeProcessedListener(processedTap);
        state = State.READY;
    }

    /** Stops early, keeping what was recorded. */
    public synchronized void stopRecording() {
        if (state == State.RECORDING) {
            targetLength = Math.min(rawLength, processedLength);
            finishRecording();
        }
    }

    public boolean hasClip() {
        return processedLength > RATE / 2;
    }

    public void playRaw() {
        play(raw, rawLength, State.PLAYING_RAW);
    }

    public void playProcessed() {
        play(processed, processedLength, State.PLAYING_PROCESSED);
    }

    private synchronized void play(float[] clip, int length, State playing) {
        if (state == State.RECORDING || !hasClip()) {
            return;
        }
        state = playing;
        playback.play(clip, length, 1.0, () -> {
            if (state == playing) {
                state = State.READY;
            }
        });
    }

    public synchronized void stopPlayback() {
        playback.stop();
        if (state == State.PLAYING_RAW || state == State.PLAYING_PROCESSED) {
            state = State.READY;
        }
    }

    /** Level summary of the recorded clips, for the result card. */
    public double[] rmsDb() {
        return new double[]{rms(raw, rawLength), rms(processed, processedLength)};
    }

    private static double rms(float[] data, int length) {
        double sum = 0;
        for (int i = 0; i < length; i++) {
            sum += data[i] * (double) data[i];
        }
        return dev.streamable.audio.dsp.Db.fromPower(sum / Math.max(1, length));
    }
}
