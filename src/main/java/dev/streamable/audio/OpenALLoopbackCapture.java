/*
 * Ported from Record-able by JoEusebe (MIT). See NOTICE for attribution.
 * Adapted for Stream-able: package, logging category and branding only -
 * the capture/encoding behaviour is intentionally unchanged.
 */
package dev.streamable.audio;

import dev.streamable.StreamAbleLog;

import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captures Minecraft's audio output by hooking into OpenAL via the
 * {@code ALC_SOFT_loopback} extension.
 *
 * <p>When a loopback device is used instead of a real audio device,
 * OpenAL renders all mixed audio into a buffer that we control.
 * We then simultaneously:</p>
 * <ol>
 *   <li>Play the audio through Java Sound ({@link SourceDataLine}) so the user hears it</li>
 *   <li>Optionally write the raw PCM to a recording stream</li>
 * </ol>
 *
 * <p>This gives us perfect, full-volume game audio capture with zero
 * dependency on system loopback devices like Stereo Mix.</p>
 */
public final class OpenALLoopbackCapture {

    /** Default sample rate matching Minecraft's OpenAL output. */
    public static final int SAMPLE_RATE = 48000;
    /** Stereo output. */
    public static final int CHANNELS = 2;
    /** 16-bit signed PCM. */
    public static final int BITS_PER_SAMPLE = 16;

    /** Render interval in milliseconds. ~10ms = low latency. */
    private static final int RENDER_INTERVAL_MS = 10;
    /** Samples per render call (per channel). At 48kHz, 10ms = 480 samples. */
    private static final int SAMPLES_PER_RENDER = SAMPLE_RATE * RENDER_INTERVAL_MS / 1000;
    /** Byte size per render: samples * channels * bytesPerSample */
    private static final int BYTES_PER_RENDER = SAMPLES_PER_RENDER * CHANNELS * (BITS_PER_SAMPLE / 8);

    private static volatile OpenALLoopbackCapture instance;

    private volatile long loopbackDevice;
    private volatile boolean running;
    private Thread renderThread;
    private SourceDataLine speakerLine;

    /** Stream to write PCM data to when recording. Null when not recording. */
    private final AtomicReference<OutputStream> recordingStream = new AtomicReference<>(null);

    private OpenALLoopbackCapture() {}

    public static OpenALLoopbackCapture getInstance() {
        if (instance == null) {
            synchronized (OpenALLoopbackCapture.class) {
                if (instance == null) {
                    instance = new OpenALLoopbackCapture();
                }
            }
        }
        return instance;
    }

    /**
     * Checks if the {@code ALC_SOFT_loopback} extension is available.
     * Must be called after OpenAL is minimally initialized (device pointer 0 = null device check).
     */
    public static boolean isLoopbackSupported() {
        try {
            return ALC10.alcIsExtensionPresent(0L, "ALC_SOFT_loopback");
        } catch (Throwable t) {
            StreamAbleLog.AUDIO.debug("ALC_SOFT_loopback extension check failed", t);
            return false;
        }
    }

    /**
     * Opens a loopback device via {@code alcLoopbackOpenDeviceSOFT}.
     * This replaces the normal {@code alcOpenDevice} call in SoundEngine.
     *
     * @return the loopback device pointer, or 0 on failure
     */
    public long openLoopbackDevice() {
        try {
            long device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence) null);
            if (device == 0L) {
                StreamAbleLog.AUDIO.error("alcLoopbackOpenDeviceSOFT returned 0 (failed)");
                return 0L;
            }

            boolean supported = SOFTLoopback.alcIsRenderFormatSupportedSOFT(
                    device,
                    SAMPLE_RATE,
                    SOFTLoopback.ALC_STEREO_SOFT,
                    SOFTLoopback.ALC_SHORT_SOFT
            );
            if (!supported) {
                StreamAbleLog.AUDIO.error("Loopback format not supported (48kHz stereo 16-bit)");
                ALC10.alcCloseDevice(device);
                return 0L;
            }

            this.loopbackDevice = device;
            StreamAbleLog.AUDIO.info("OpenAL loopback device opened successfully: {}", device);
            return device;
        } catch (Throwable t) {
            StreamAbleLog.AUDIO.error("Failed to open loopback device", t);
            return 0L;
        }
    }

    /**
     * Returns the OpenAL context attributes needed for a loopback device.
     * These must be passed to {@code alcCreateContext} instead of the normal attributes.
     */
    public int[] getContextAttributes() {
        return new int[]{
                SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT, SOFTLoopback.ALC_STEREO_SOFT,
                SOFTLoopback.ALC_FORMAT_TYPE_SOFT, SOFTLoopback.ALC_SHORT_SOFT,
                ALC10.ALC_FREQUENCY, SAMPLE_RATE,
                0 // terminator
        };
    }

    /**
     * Starts the render thread that pulls audio from the loopback device
     * and plays it through Java Sound.
     */
    public void startRenderThread() {
        if (running) {
            return;
        }
        if (loopbackDevice == 0L) {
            StreamAbleLog.AUDIO.warn("Cannot start render thread: no loopback device");
            return;
        }

        running = true;
        renderThread = new Thread(this::renderLoop, "Stream-able Audio Render");
        renderThread.setDaemon(true);
        renderThread.setPriority(Thread.MAX_PRIORITY - 1);
        renderThread.start();
        StreamAbleLog.AUDIO.info("Loopback render thread started");
    }

    /**
     * Stops the render thread and closes the speaker line.
     */
    public void stopRenderThread() {
        running = false;
        if (renderThread != null) {
            try {
                renderThread.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            renderThread = null;
        }
        closeSpeakerLine();
        StreamAbleLog.AUDIO.info("Loopback render thread stopped");
    }

    /**
     * Sets the output stream for recording. Pass null to stop recording.
     */
    public void setRecordingStream(OutputStream stream) {
        recordingStream.set(stream);
    }

    /**
     * Returns true if a loopback device is active and the render thread is running.
     */
    public boolean isActive() {
        return running && loopbackDevice != 0L;
    }

    /**
     * Returns true if loopback is available and was successfully initialized.
     */
    public boolean hasLoopbackDevice() {
        return loopbackDevice != 0L;
    }

    private void renderLoop() {
        StreamAbleLog.AUDIO.info("Render loop starting: {}Hz {}ch {}bit, {} samples/render, {} bytes/render",
                SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE, SAMPLES_PER_RENDER, BYTES_PER_RENDER);

        ByteBuffer renderBuffer = ByteBuffer.allocateDirect(BYTES_PER_RENDER);
        renderBuffer.order(ByteOrder.nativeOrder());

        byte[] audioBytes = new byte[BYTES_PER_RENDER];

        if (!openSpeakerLine()) {
            StreamAbleLog.AUDIO.error("Failed to open speaker line, audio will be silent");
        }

        long startNanos = System.nanoTime();
        long samplesRendered = 0L;

        while (running) {
            try {
                long device = this.loopbackDevice;
                if (device == 0L) {
                    Thread.sleep(50);
                    startNanos = System.nanoTime();
                    samplesRendered = 0L;
                    continue;
                }

                long expectedNanos = startNanos + (samplesRendered * 1_000_000_000L / SAMPLE_RATE);
                long nowNanos = System.nanoTime();
                long waitNanos = expectedNanos - nowNanos;

                if (waitNanos > 1_000_000L) { // > 1ms ahead
                    Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
                } else if (waitNanos < -500_000_000L) {
                    startNanos = System.nanoTime();
                    samplesRendered = 0L;
                }

                renderBuffer.clear();
                SOFTLoopback.alcRenderSamplesSOFT(device, renderBuffer, SAMPLES_PER_RENDER);
                samplesRendered += SAMPLES_PER_RENDER;

                renderBuffer.rewind();
                renderBuffer.get(audioBytes);

                // FIX: Write to recording stream FIRST (higher priority).
                // Previously, speakerLine.write() could block when its buffer
                // was full (only 20ms buffer), which would also delay/block
                // the recording stream write, causing audio gaps in recordings.
                OutputStream stream = recordingStream.get();
                if (stream != null) {
                    try {
                        stream.write(audioBytes);
                    } catch (Throwable t) {
                        StreamAbleLog.AUDIO.debug("Recording stream write failed", t);
                    }
                }

                // Speaker playback second (can block without affecting recording)
                if (speakerLine != null && speakerLine.isOpen()) {
                    speakerLine.write(audioBytes, 0, audioBytes.length);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                StreamAbleLog.AUDIO.warn("Render loop error", t);
                try { Thread.sleep(50); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        StreamAbleLog.AUDIO.info("Render loop exited");
    }

    private boolean openSpeakerLine() {
        try {
            AudioFormat format = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    SAMPLE_RATE,
                    BITS_PER_SAMPLE,
                    CHANNELS,
                    CHANNELS * (BITS_PER_SAMPLE / 8), // frame size
                    SAMPLE_RATE,
                    false // little-endian
            );

            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                StreamAbleLog.AUDIO.warn("Java Sound does not support format: {}", format);
                return false;
            }

            speakerLine = (SourceDataLine) AudioSystem.getLine(info);
            // FIX: Increased buffer from 2x (20ms) to 8x (80ms) to reduce
            // frequency of blocking writes that could stall recording.
            int bufferSize = BYTES_PER_RENDER * 8;
            speakerLine.open(format, bufferSize);
            speakerLine.start();
            StreamAbleLog.AUDIO.info("Java Sound speaker line opened: {}", format);
            return true;
        } catch (Throwable t) {
            StreamAbleLog.AUDIO.error("Failed to open Java Sound speaker line", t);
            speakerLine = null;
            return false;
        }
    }

    private void closeSpeakerLine() {
        if (speakerLine != null) {
            try {
                speakerLine.stop();
                speakerLine.close();
            } catch (Throwable ignored) {}
            speakerLine = null;
        }
    }

    /**
     * Shuts down the entire loopback capture system.
     * Called when the game closes.
     */
    public void shutdown() {
        stopRenderThread();
        recordingStream.set(null);
        loopbackDevice = 0L;
        instance = null;
    }
}
