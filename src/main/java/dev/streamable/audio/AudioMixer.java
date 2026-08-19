package dev.streamable.audio;

import dev.streamable.StreamAbleLog;

import java.io.OutputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Mixes the input buses into one stereo program stream for the broadcast.
 *
 * <pre>
 *   Game ───────────┐
 *   Microphone ─────┤
 *   Plasmo Voice ───┼──&gt; program mix ──&gt; AAC ──&gt; stream
 *   Browser audio ──┘
 * </pre>
 *
 * <p>Local recording keeps Record-able's separate-track behaviour and does not
 * go through this mixer: streams accept a single audio track, recordings do
 * not have to give that up.</p>
 *
 * <h2>Timing</h2>
 * <p>The mixer is clocked by the <em>game</em> bus, which is the only source
 * guaranteed to be continuous (OpenAL loopback renders whether or not anything
 * is audible). Each block of game audio defines a slice of the timeline; other
 * buses contribute whatever they have queued for that slice, and silence
 * otherwise. That keeps the byte count - and therefore FFmpeg's derived
 * timestamps - exactly proportional to elapsed time, which is what prevents the
 * microphone from drifting away from the video over a long session.</p>
 */
public final class AudioMixer {

    public static final int SAMPLE_RATE = OpenALLoopbackCapture.SAMPLE_RATE;
    public static final int CHANNELS = OpenALLoopbackCapture.CHANNELS;
    public static final int BYTES_PER_SAMPLE = 2;
    private static final int FRAME_BYTES = CHANNELS * BYTES_PER_SAMPLE;

    /** How often the mixer emits a block. Small enough to keep latency low. */
    private static final long TICK_MILLIS = 20;

    private final Map<AudioBus.Kind, AudioBus> buses = new EnumMap<>(AudioBus.Kind.class);
    private final Map<AudioBus.Kind, PcmRingBuffer> pending = new EnumMap<>(AudioBus.Kind.class);
    private final CopyOnWriteArrayList<Consumer<byte[]>> sinks = new CopyOnWriteArrayList<>();
    private volatile boolean active;
    private Thread clockThread;
    private long startNanos;
    private long framesEmitted;

    public AudioMixer() {
        for (AudioBus.Kind kind : AudioBus.Kind.values()) {
            buses.put(kind, new AudioBus(kind));
            pending.put(kind, new PcmRingBuffer(SAMPLE_RATE * FRAME_BYTES));  // 1 second
        }
    }

    public AudioBus bus(AudioBus.Kind kind) {
        return buses.get(kind);
    }

    public Iterable<AudioBus> buses() {
        return buses.values();
    }

    /** Registers a destination for the mixed program stream. */
    public void addSink(Consumer<byte[]> sink) {
        sinks.add(sink);
    }

    public void removeSink(Consumer<byte[]> sink) {
        sinks.remove(sink);
    }

    /**
     * Starts the mixer clock.
     *
     * <p>The mixer is driven by a timer rather than by incoming game audio.
     * That matters more than it sounds: FFmpeg is given a continuous
     * {@code s16le} input, and if that input ever goes quiet the muxer stalls
     * waiting for audio to interleave against the video - the stream simply
     * stops. Emitting silence when nothing is playing keeps the byte count
     * exactly proportional to elapsed time, so the stream stays alive and the
     * derived timestamps stay correct whether or not anything is audible, and
     * whether or not game-audio capture is even available.</p>
     */
    public synchronized void start() {
        if (active) {
            return;
        }
        buses.values().forEach(AudioBus::reset);
        pending.values().forEach(PcmRingBuffer::clear);
        startNanos = System.nanoTime();
        framesEmitted = 0;
        active = true;
        clockThread = Thread.ofPlatform()
                .name("stream-able-audio-mixer")
                .daemon(true)
                .start(this::runClock);
    }

    public synchronized void stop() {
        active = false;
        if (clockThread != null) {
            clockThread.interrupt();
            clockThread = null;
        }
        pending.values().forEach(PcmRingBuffer::clear);
    }

    private void runClock() {
        while (active) {
            try {
                Thread.sleep(TICK_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                emitDue();
            } catch (RuntimeException e) {
                StreamAbleLog.AUDIO.warn("Audio mixer tick failed", e);
            }
        }
    }

    /** Emits exactly as many frames as wall-clock time says are owed. */
    private void emitDue() {
        long elapsedNanos = System.nanoTime() - startNanos;
        long dueFrames = elapsedNanos * SAMPLE_RATE / 1_000_000_000L;
        int missing = (int) Math.min(dueFrames - framesEmitted, SAMPLE_RATE);
        if (missing <= 0) {
            return;
        }
        framesEmitted += missing;
        emitBlock(missing * FRAME_BYTES);
    }

    /** Mixes every bus's queued audio into one block and publishes it. */
    private void emitBlock(int byteCount) {
        byte[] mixed = new byte[byteCount];      // starts as silence
        byte[] scratch = new byte[byteCount];
        for (AudioBus.Kind kind : AudioBus.Kind.values()) {
            AudioBus bus = buses.get(kind);
            float gain = bus.effectiveGain();
            int read = pending.get(kind).read(scratch, byteCount);
            if (read <= 0 || gain <= 0) {
                continue;
            }
            float peak = mixInto(mixed, scratch, read, gain, false);
            bus.noteSamples(read / FRAME_BYTES, peak);
        }
        for (Consumer<byte[]> sink : sinks) {
            try {
                sink.accept(mixed);
            } catch (RuntimeException e) {
                StreamAbleLog.AUDIO.warn("Audio sink rejected a block", e);
            }
        }
    }

    public boolean isActive() {
        return active;
    }

    /** Submits audio for a bus; buffered until the next mixer tick consumes it. */
    public void submit(AudioBus.Kind kind, byte[] pcm, int length) {
        if (!active || length <= 0) {
            return;
        }
        pending.get(kind).write(pcm, length);
    }

    /** Submits a block of game audio. Buffered like any other bus. */
    public void submitGameAudio(byte[] pcm, int length) {
        submit(AudioBus.Kind.GAME, pcm, length);
    }

    /**
     * Adds {@code source} into {@code target} with gain, saturating rather than
     * wrapping. Wrapping would turn a loud moment into a burst of noise.
     *
     * @param replace when true the target is overwritten instead of summed
     * @return peak level of the contribution, in {@code [0,1]}
     */
    private static float mixInto(byte[] target, byte[] source, int length, float gain, boolean replace) {
        int peak = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((source[i] & 0xFF) | (source[i + 1] << 8));
            int scaled = Math.round(sample * gain);
            int existing = replace ? 0 : (short) ((target[i] & 0xFF) | (target[i + 1] << 8));
            int sum = Math.clamp(existing + scaled, Short.MIN_VALUE, Short.MAX_VALUE);
            target[i] = (byte) (sum & 0xFF);
            target[i + 1] = (byte) ((sum >> 8) & 0xFF);
            peak = Math.max(peak, Math.abs(scaled));
        }
        return Math.min(1.0f, peak / (float) Short.MAX_VALUE);
    }

    /**
     * An {@link OutputStream} view of the game bus, so
     * {@link OpenALLoopbackCapture#setRecordingStream(OutputStream)} can drive
     * the mixer directly.
     */
    public OutputStream gameAudioStream() {
        return new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                if (off == 0) {
                    submitGameAudio(b, len);
                } else {
                    byte[] slice = new byte[len];
                    System.arraycopy(b, off, slice, 0, len);
                    submitGameAudio(slice, len);
                }
            }
        };
    }

    /** Fixed-capacity PCM buffer that drops the oldest audio when it overflows. */
    private static final class PcmRingBuffer {
        private final byte[] data;
        private int size;

        PcmRingBuffer(int capacity) {
            this.data = new byte[capacity];
        }

        synchronized void write(byte[] source, int length) {
            int usable = Math.min(length, data.length);
            if (size + usable > data.length) {
                // Discard the oldest audio: a late microphone block is worth
                // less than keeping the stream in sync.
                int drop = size + usable - data.length;
                System.arraycopy(data, drop, data, 0, size - drop);
                size -= drop;
            }
            System.arraycopy(source, 0, data, size, usable);
            size += usable;
        }

        synchronized int read(byte[] target, int wanted) {
            int available = Math.min(wanted, size);
            if (available <= 0) {
                return 0;
            }
            System.arraycopy(data, 0, target, 0, available);
            System.arraycopy(data, available, data, 0, size - available);
            size -= available;
            return available;
        }

        synchronized void clear() {
            size = 0;
        }
    }
}
