package dev.streamable.audio.mic;

import dev.streamable.StreamAbleLog;
import dev.streamable.audio.JavaAudioCapture;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Capture device list, enumerated off the render thread.
 *
 * <p>Java Sound's native enumeration can block for a long time - on some ALSA
 * setups indefinitely - so it is never called from a UI or render path. The UI
 * reads the cached list and asks for a background refresh.</p>
 */
public final class MicrophoneDevices {

    private volatile List<JavaAudioCapture.AudioDeviceInfo> devices = List.of();
    private volatile boolean scanned;
    private final AtomicBoolean scanning = new AtomicBoolean();

    public List<JavaAudioCapture.AudioDeviceInfo> devices() {
        return devices;
    }

    public boolean isScanning() {
        return scanning.get();
    }

    public boolean hasScanned() {
        return scanned;
    }

    /** Starts a scan unless one is running. Never blocks the caller. */
    public void refreshAsync() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        Thread.ofPlatform().name("stream-able-mic-scan").daemon(true).start(() -> {
            try {
                devices = List.copyOf(JavaAudioCapture.detectAudioDevices());
                scanned = true;
                StreamAbleLog.AUDIO.debug("Found {} capture device(s).", devices.size());
            } catch (RuntimeException | LinkageError e) {
                StreamAbleLog.AUDIO.warn("Could not enumerate microphones", e);
            } finally {
                scanning.set(false);
            }
        });
    }

    public JavaAudioCapture.AudioDeviceInfo find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (JavaAudioCapture.AudioDeviceInfo device : devices) {
            if (device.name().equals(name) || device.displayName().equals(name)) {
                return device;
            }
        }
        return null;
    }
}
