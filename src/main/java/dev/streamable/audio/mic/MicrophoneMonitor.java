package dev.streamable.audio.mic;

import dev.streamable.StreamAbleLog;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Optional live monitoring of the microphone on the default output.
 *
 * <p>Off by default and never persisted as on: with speakers instead of
 * headphones it feeds back. The Audio page shows a warning while it runs. The
 * output line is small (30 ms) and fed from a bounded queue, so it never
 * delays the DSP worker; if the output device stalls, monitor audio is
 * dropped, not the microphone.</p>
 */
public final class MicrophoneMonitor {

    private static final AudioFormat FORMAT =
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48_000, 16, 1, 2, 48_000, false);

    private final MicrophoneProcessor processor;
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(8);
    private volatile boolean running;
    private volatile boolean processedSignal = true;
    private volatile double volume = 0.8;
    private Thread thread;
    private final BlockListener rawTap = (samples, length) -> enqueue(samples, length, false);
    private final BlockListener processedTap = (samples, length) -> enqueue(samples, length, true);

    public MicrophoneMonitor(MicrophoneProcessor processor) {
        this.processor = processor;
    }

    public boolean isRunning() {
        return running;
    }

    public void configure(boolean processed, double volume) {
        this.processedSignal = processed;
        this.volume = volume;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        queue.clear();
        processor.addRawListener(rawTap);
        processor.addProcessedListener(processedTap);
        thread = Thread.ofPlatform().name("stream-able-mic-monitor").daemon(true).start(this::run);
    }

    private void enqueue(float[] samples, int length, boolean processed) {
        if (!running || processed != processedSignal) {
            return;
        }
        byte[] pcm = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            int v = (int) Math.round(Math.clamp(samples[i] * volume, -1.0, 1.0) * 32767);
            pcm[2 * i] = (byte) v;
            pcm[2 * i + 1] = (byte) (v >> 8);
        }
        queue.offer(pcm);   // dropped if the output device is behind
    }

    private void run() {
        try (SourceDataLine line = AudioSystem.getSourceDataLine(FORMAT)) {
            line.open(FORMAT, 48_000 * 2 * 30 / 1000);
            line.start();
            while (running) {
                byte[] pcm = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (pcm != null) {
                    line.write(pcm, 0, pcm.length);
                }
            }
            line.stop();
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            StreamAbleLog.AUDIO.warn("Microphone monitoring unavailable: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
            processor.removeRawListener(rawTap);
            processor.removeProcessedListener(processedTap);
        }
    }

    public synchronized void stop() {
        running = false;
        processor.removeRawListener(rawTap);
        processor.removeProcessedListener(processedTap);
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
}
