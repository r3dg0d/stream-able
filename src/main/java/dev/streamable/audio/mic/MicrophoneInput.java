package dev.streamable.audio.mic;

import dev.streamable.StreamAbleLog;
import dev.streamable.audio.JavaAudioCapture;
import dev.streamable.audio.dsp.SincResampler;
import dev.streamable.config.MicrophoneSettings;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/**
 * Opens a capture device and feeds 48 kHz mono float blocks to the processor.
 *
 * <p>Opening a device can block in native code, so {@link #startAsync} does it
 * on its own thread; the game never waits for a microphone. Reads are 10 ms
 * with a 40 ms device buffer, which is enough to ride out scheduler jitter
 * without adding perceptible latency. Devices that cannot run at 48 kHz are
 * resampled with the band-limited {@link SincResampler}.</p>
 */
public final class MicrophoneInput implements AutoCloseable {

    private static final AudioFormat[] CANDIDATES = {
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48_000, 16, 1, 2, 48_000, false),
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48_000, 16, 2, 4, 48_000, false),
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44_100, 16, 1, 2, 44_100, false),
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44_100, 16, 2, 4, 44_100, false),
    };

    private final MicrophoneProcessor processor;
    private final MicrophoneDevices devices;
    private final ChannelSelector channels = new ChannelSelector();
    private volatile boolean running;
    private volatile TargetDataLine line;
    private Thread thread;
    private volatile String status = "Not capturing.";
    private volatile String activeDevice = "";
    private volatile AudioFormat activeFormat;

    public MicrophoneInput(MicrophoneProcessor processor, MicrophoneDevices devices) {
        this.processor = processor;
        this.devices = devices;
    }

    public boolean isRunning() {
        return running;
    }

    public String status() {
        return status;
    }

    public String activeDevice() {
        return activeDevice;
    }

    public AudioFormat activeFormat() {
        return activeFormat;
    }

    public ChannelSelector channels() {
        return channels;
    }

    /** Opens the device and starts capture on a background thread. */
    public synchronized void startAsync(MicrophoneSettings settings) {
        if (running) {
            return;
        }
        running = true;
        status = "Opening the microphone...";
        thread = Thread.ofPlatform().name("stream-able-mic-capture").daemon(true).start(() -> run(settings));
    }

    private void run(MicrophoneSettings settings) {
        JavaAudioCapture.AudioDeviceInfo device = devices.find(settings.device);
        for (AudioFormat format : CANDIDATES) {
            if (!running) {
                return;
            }
            TargetDataLine candidate = open(device, format);
            if (candidate == null) {
                continue;
            }
            line = candidate;
            activeFormat = format;
            activeDevice = device == null ? "System default" : device.displayName();
            status = "Capturing from " + activeDevice + " (" + (int) format.getSampleRate() + " Hz, "
                    + (format.getChannels() == 1 ? "mono" : "stereo") + ").";
            StreamAbleLog.AUDIO.info("Microphone: {}", status);
            capture(candidate, format, settings);
            return;
        }
        status = device == null && !settings.device.isBlank()
                ? "Microphone '" + settings.device + "' was not found."
                : "The microphone could not be opened (in use by another program, or no supported format).";
        StreamAbleLog.AUDIO.warn("Microphone unavailable: {}", status);
        running = false;
    }

    private static TargetDataLine open(JavaAudioCapture.AudioDeviceInfo device, AudioFormat format) {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        try {
            TargetDataLine candidate = device == null
                    ? (TargetDataLine) AudioSystem.getLine(info)
                    : (TargetDataLine) AudioSystem.getMixer(device.mixerInfo()).getLine(info);
            int frameBytes = format.getFrameSize();
            int bufferFrames = (int) (format.getSampleRate() * 0.04);   // 40 ms
            candidate.open(format, bufferFrames * frameBytes);
            candidate.start();
            return candidate;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            StreamAbleLog.AUDIO.debug("Microphone format {} unavailable: {}", format, e.toString());
            return null;
        }
    }

    private void capture(TargetDataLine device, AudioFormat format, MicrophoneSettings settings) {
        int channelCount = format.getChannels();
        int rate = (int) format.getSampleRate();
        int frames = rate / 100;                                   // 10 ms
        byte[] bytes = new byte[frames * format.getFrameSize()];
        short[] samples = new short[frames * channelCount];
        float[] mono = new float[frames];
        SincResampler resampler = rate == 48_000 ? null : new SincResampler(rate, 48_000);
        float[] resampled = resampler == null ? null : new float[resampler.maxOutput(frames) + 4];
        try {
            while (running) {
                int read = device.read(bytes, 0, bytes.length);
                if (read <= 0) {
                    continue;
                }
                int readFrames = read / format.getFrameSize();
                for (int i = 0; i < readFrames * channelCount; i++) {
                    samples[i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8));
                }
                int n = channels.toMono(samples, readFrames, channelCount, settings.inputChannel, mono);
                if (resampler == null) {
                    processor.submit(mono, 0, n);
                } else {
                    int out = resampler.process(mono, 0, n, resampled);
                    processor.submit(resampled, 0, out);
                }
            }
        } catch (RuntimeException e) {
            if (running) {
                status = "Microphone read failed: " + e.getMessage();
                StreamAbleLog.AUDIO.warn("Microphone capture stopped after an error", e);
            }
        } finally {
            try {
                device.stop();
                device.close();
            } catch (RuntimeException e) {
                StreamAbleLog.AUDIO.debug("Error closing the microphone: {}", e.toString());
            }
            running = false;
            line = null;
        }
    }

    public synchronized void stop() {
        running = false;
        TargetDataLine current = line;
        if (current != null) {
            try {
                current.stop();   // unblocks read()
            } catch (RuntimeException ignored) {
                // already closing
            }
        }
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        activeDevice = "";
        activeFormat = null;
        status = "Not capturing.";
    }

    @Override
    public void close() {
        stop();
    }
}
