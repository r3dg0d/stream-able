package dev.streamable.audio;

import dev.streamable.StreamAbleLog;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures the microphone into the program mix.
 *
 * <p>Stream-able previously had no microphone capture of its own: the
 * microphone bus was fed only by the Plasmo Voice integration, so the mic was
 * recorded only while connected to a voice server <em>and</em> actively
 * transmitting. In singleplayer, or on any server without voice chat, the
 * setting was on and nothing was captured. This is the missing source.</p>
 *
 * <p>Audio is read on its own thread, gain-adjusted, converted to the mixer's
 * 48 kHz stereo format and handed over as a normal bus. Nothing here blocks the
 * game: if the device cannot be opened, capture simply does not start and the
 * reason is reported to the UI.</p>
 */
public final class MicrophoneCapture implements AutoCloseable {

    /** Preferred formats, best first; the first the device supports is used. */
    private static final AudioFormat[] CANDIDATE_FORMATS = {
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48_000, 16, 2, 4, 48_000, false),
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48_000, 16, 1, 2, 48_000, false),
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44_100, 16, 2, 4, 44_100, false),
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44_100, 16, 1, 2, 44_100, false),
    };

    /** ~20 ms of audio per read, matching the mixer's tick. */
    private static final int READ_MILLIS = 20;

    private final AudioMixer mixer;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private TargetDataLine line;
    private Thread readerThread;
    private volatile float gain = 1.0f;
    private volatile String status = "Not capturing.";
    private volatile String activeDevice = "";

    public MicrophoneCapture(AudioMixer mixer) {
        this.mixer = mixer;
    }

    /** Input devices offered in the device selector. */
    public static List<JavaAudioCapture.AudioDeviceInfo> availableDevices() {
        try {
            return JavaAudioCapture.detectAudioDevices();
        } catch (RuntimeException e) {
            StreamAbleLog.AUDIO.warn("Could not enumerate microphone devices", e);
            return List.of();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Human-readable state for the Audio settings section. */
    public String status() {
        return status;
    }

    public String activeDevice() {
        return activeDevice;
    }

    /** 0-400%, matching Record-able's gain range. */
    public void setGainPercent(int percent) {
        this.gain = Math.clamp(percent, 0, 400) / 100.0f;
    }

    /**
     * Opens the microphone and starts feeding the mixer.
     *
     * @param deviceName the device to open, or blank for the system default
     * @return {@code true} if capture started
     */
    public synchronized boolean start(String deviceName, int gainPercent) {
        if (running.get()) {
            return true;
        }
        setGainPercent(gainPercent);
        try {
            Mixer.Info selected = findMixer(deviceName);
            for (AudioFormat format : CANDIDATE_FORMATS) {
                TargetDataLine candidate = openLine(selected, format);
                if (candidate == null) {
                    continue;
                }
                line = candidate;
                line.start();
                running.set(true);
                activeDevice = selected == null ? "System default" : selected.getName();
                status = "Capturing from " + activeDevice + " ("
                        + (int) format.getSampleRate() + " Hz, "
                        + (format.getChannels() == 1 ? "mono" : "stereo") + ").";
                StreamAbleLog.AUDIO.info("Microphone capture started: {}", status);

                readerThread = Thread.ofPlatform()
                        .name("stream-able-microphone")
                        .daemon(true)
                        .start(() -> readLoop(format));
                return true;
            }
            status = "No supported audio format on " + (deviceName.isBlank() ? "the default device" : deviceName) + ".";
            StreamAbleLog.AUDIO.warn("Microphone capture unavailable: {}", status);
            return false;
        } catch (RuntimeException e) {
            status = "Could not open the microphone: " + e.getMessage();
            StreamAbleLog.AUDIO.warn("Microphone capture failed to start", e);
            return false;
        }
    }

    private TargetDataLine openLine(Mixer.Info mixerInfo, AudioFormat format) {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        try {
            TargetDataLine candidate = mixerInfo == null
                    ? (TargetDataLine) AudioSystem.getLine(info)
                    : (TargetDataLine) AudioSystem.getMixer(mixerInfo).getLine(info);
            candidate.open(format, (int) format.getSampleRate() * format.getFrameSize() / 4);
            return candidate;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            // Expected while probing formats, and when another application holds
            // the device exclusively.
            StreamAbleLog.AUDIO.debug("Microphone format {} unavailable: {}", format, e.toString());
            return null;
        }
    }

    private static Mixer.Info findMixer(String deviceName) {
        if (deviceName == null || deviceName.isBlank()) {
            return null;
        }
        for (JavaAudioCapture.AudioDeviceInfo device : availableDevices()) {
            if (device.name().equals(deviceName) || device.displayName().equals(deviceName)) {
                return device.mixerInfo();
            }
        }
        StreamAbleLog.AUDIO.warn("Microphone '{}' not found; using the system default.", deviceName);
        return null;
    }

    private void readLoop(AudioFormat format) {
        int frameBytes = format.getFrameSize();
        int bytesPerRead = Math.max(frameBytes,
                (int) (format.getSampleRate() * frameBytes * READ_MILLIS / 1000) / frameBytes * frameBytes);
        byte[] buffer = new byte[bytesPerRead];
        int channels = format.getChannels();
        int sampleRate = (int) format.getSampleRate();

        while (running.get()) {
            try {
                int read = line.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                short[] samples = toSamples(buffer, read, format.isBigEndian(), gain);
                byte[] pcm = VoicePcmConverter.toMixerFormat(samples, channels, sampleRate);
                if (pcm.length > 0) {
                    mixer.submit(AudioBus.Kind.MICROPHONE, pcm, pcm.length);
                }
            } catch (RuntimeException e) {
                if (running.get()) {
                    status = "Microphone read failed: " + e.getMessage();
                    StreamAbleLog.AUDIO.warn("Microphone capture stopped after an error", e);
                }
                return;
            }
        }
    }

    /** Unpacks 16-bit PCM and applies gain, saturating rather than wrapping. */
    static short[] toSamples(byte[] buffer, int length, boolean bigEndian, float gain) {
        int count = length / 2;
        short[] samples = new short[count];
        for (int i = 0; i < count; i++) {
            int low = buffer[i * 2] & 0xFF;
            int high = buffer[i * 2 + 1] & 0xFF;
            int value = bigEndian ? (low << 8) | high : (high << 8) | low;
            int sample = (short) value;
            if (gain != 1.0f) {
                sample = Math.clamp(Math.round(sample * gain), Short.MIN_VALUE, Short.MAX_VALUE);
            }
            samples[i] = (short) sample;
        }
        return samples;
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            if (line != null) {
                line.stop();
                line.close();
                line = null;
            }
        } catch (RuntimeException e) {
            StreamAbleLog.AUDIO.debug("Error closing the microphone line: {}", e.toString());
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        activeDevice = "";
        status = "Not capturing.";
        StreamAbleLog.AUDIO.info("Microphone capture stopped.");
    }

    @Override
    public void close() {
        stop();
    }
}
