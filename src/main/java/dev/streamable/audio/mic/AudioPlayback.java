package dev.streamable.audio.mic;

import dev.streamable.StreamAbleLog;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays 48 kHz mono audio to the default output device on its own thread.
 * Used for the microphone test (raw vs processed) - local only, nothing is
 * recorded or streamed.
 */
public final class AudioPlayback {

    private static final AudioFormat FORMAT =
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48_000, 16, 1, 2, 48_000, false);

    private volatile Thread thread;
    private volatile boolean stopRequested;
    private volatile double progress;

    public boolean isPlaying() {
        Thread current = thread;
        return current != null && current.isAlive();
    }

    /** 0..1 through the clip, for a playhead. */
    public double progress() {
        return progress;
    }

    public synchronized void play(float[] samples, int length, double volume, Runnable onDone) {
        stop();
        stopRequested = false;
        progress = 0;
        thread = Thread.ofPlatform().name("stream-able-mic-playback").daemon(true).start(() -> {
            try (SourceDataLine line = AudioSystem.getSourceDataLine(FORMAT)) {
                line.open(FORMAT, 48_000 / 5 * 2);
                line.start();
                byte[] chunk = new byte[960];
                for (int i = 0; i < length && !stopRequested; i += 480) {
                    int n = Math.min(480, length - i);
                    for (int k = 0; k < n; k++) {
                        int v = (int) Math.round(Math.clamp(samples[i + k] * volume, -1.0, 1.0) * 32767);
                        chunk[2 * k] = (byte) v;
                        chunk[2 * k + 1] = (byte) (v >> 8);
                    }
                    line.write(chunk, 0, n * 2);
                    progress = (i + n) / (double) length;
                }
                if (!stopRequested) {
                    line.drain();
                }
                line.stop();
            } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
                StreamAbleLog.AUDIO.warn("Could not play the microphone test: {}", e.toString());
            } finally {
                progress = 1;
                if (onDone != null) {
                    onDone.run();
                }
            }
        });
    }

    public synchronized void stop() {
        stopRequested = true;
        Thread current = thread;
        if (current != null) {
            try {
                current.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }
}
