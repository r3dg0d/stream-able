package dev.streamable.audio;

import dev.streamable.StreamAbleLog;

import java.io.OutputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Mixes the input buses into one stereo program stream for recordings and the
 * broadcast.
 *
 * <pre>
 *   Game ───────────┐
 *   Microphone ─────┤
 *   Voice chat ─────┼──&gt; program mix ──&gt; recording WAV / live AAC
 *   Browser audio ──┘
 * </pre>
 *
 * <h2>Timing</h2>
 * <p>The mixer is clocked by wall-clock time, not by any input: every tick
 * emits exactly the frames elapsed time calls for, mixing in whatever each bus
 * has queued and padding with silence otherwise, so the byte count - and
 * FFmpeg's derived timestamps - stay exactly proportional to elapsed time.</p>
 *
 * <h2>Several sources on one bus</h2>
 * <p>Buses such as voice chat carry several independent streams at once (one
 * per speaking player; one per browser source). Those are submitted with a
 * key; each key has its own queue and the queues are <em>summed</em> sample by
 * sample, so two people talking at once are heard together rather than one
 * after the other.</p>
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
    private final Map<AudioBus.Kind, CopyOnWriteArrayList<Consumer<byte[]>>> busSinks = new EnumMap<>(AudioBus.Kind.class);
    private final Map<AudioBus.Kind, java.util.concurrent.atomic.AtomicLong> trimmedBytes = new EnumMap<>(AudioBus.Kind.class);
    private final Map<AudioBus.Kind, java.util.concurrent.ConcurrentHashMap<Object, KeyedSource>> keyed =
            new EnumMap<>(AudioBus.Kind.class);
    /** A keyed source with nothing queued for this long is forgotten. */
    static final long KEYED_IDLE_NANOS = 5_000_000_000L;
    private volatile boolean active;
    private Thread clockThread;
    private long startNanos;
    private long framesEmitted;

    public AudioMixer() {
        for (AudioBus.Kind kind : AudioBus.Kind.values()) {
            buses.put(kind, new AudioBus(kind));
            pending.put(kind, new PcmRingBuffer(SAMPLE_RATE * FRAME_BYTES));  // 1 second
            busSinks.put(kind, new CopyOnWriteArrayList<>());
            trimmedBytes.put(kind, new java.util.concurrent.atomic.AtomicLong());
            keyed.put(kind, new java.util.concurrent.ConcurrentHashMap<>());
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
     * Receives one bus's post-gain contribution for every mixer block - silence
     * when the bus is quiet - so a separate track stays sample-aligned with the
     * program mix.
     */
    public void addBusSink(AudioBus.Kind kind, Consumer<byte[]> sink) {
        busSinks.get(kind).add(sink);
    }

    public void removeBusSink(AudioBus.Kind kind, Consumer<byte[]> sink) {
        busSinks.get(kind).remove(sink);
    }

    /**
     * Largest backlog a bus may hold before the oldest audio is trimmed.
     *
     * <p>Each capture device runs on its own crystal, so over a long session a
     * microphone delivers slightly more or fewer samples than the mixer's
     * wall-clock consumes. Without a bound that difference accumulates as
     * latency. Trimming the backlog of one bus never touches the mixer clock,
     * so the program stream's timing stays exact.</p>
     */
    static int maxBacklogBytes(AudioBus.Kind kind) {
        int millis = switch (kind) {
            case MICROPHONE -> 80;
            case VOICE_CHAT -> 200;
            case GAME, BROWSER -> 200;
        };
        return SAMPLE_RATE * millis / 1000 * FRAME_BYTES;
    }

    /** Bytes of this bus trimmed to hold its latency bound (diagnostics). */
    public long trimmedBytes(AudioBus.Kind kind) {
        return trimmedBytes.get(kind).get();
    }

    /** Current backlog of a bus in milliseconds (diagnostics). */
    public double backlogMillis(AudioBus.Kind kind) {
        return pending.get(kind).size() / (double) FRAME_BYTES * 1000.0 / SAMPLE_RATE;
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
        keyed.values().forEach(java.util.Map::clear);
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
        keyed.values().forEach(java.util.Map::clear);
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
    void emitDue() {
        emitDue(System.nanoTime());
    }

    /**
     * Emits every sample frame owed by {@code nowNanos}, in blocks of at most
     * one second. Owed time is never discarded: after a stall the backlog is
     * emitted (as silence where buses have nothing), so the byte count - and
     * FFmpeg's derived timestamps - stay locked to elapsed time.
     */
    void emitDue(long nowNanos) {
        long elapsedNanos = nowNanos - startNanos;
        long dueFrames = elapsedNanos * SAMPLE_RATE / 1_000_000_000L;
        while (dueFrames - framesEmitted > 0) {
            int missing = (int) Math.min(dueFrames - framesEmitted, SAMPLE_RATE);
            framesEmitted += missing;
            emitBlock(missing * FRAME_BYTES);
        }
    }

    /** Test seam: starts the clock at a given instant without the timer thread. */
    synchronized void startClockForTesting(long startNanos) {
        buses.values().forEach(AudioBus::reset);
        pending.values().forEach(PcmRingBuffer::clear);
        keyed.values().forEach(java.util.Map::clear);
        this.startNanos = startNanos;
        framesEmitted = 0;
        active = true;
    }

    public long framesEmitted() {
        return framesEmitted;
    }

    /** Mixes every bus's queued audio into one block and publishes it. */
    private void emitBlock(int byteCount) {
        byte[] mixed = new byte[byteCount];      // starts as silence
        byte[] scratch = new byte[byteCount];
        long now = System.nanoTime();
        for (AudioBus.Kind kind : AudioBus.Kind.values()) {
            AudioBus bus = buses.get(kind);
            float gain = bus.effectiveGain();
            byte[] busPcm = new byte[byteCount];
            int read = drain(kind, pending.get(kind), scratch, byteCount);
            if (read > 0) {
                mixInto(busPcm, scratch, read, 1f, true);
            }
            var sources = keyed.get(kind);
            for (var entry : sources.entrySet()) {
                KeyedSource source = entry.getValue();
                int got = drain(kind, source.buffer, scratch, byteCount);
                if (got > 0) {
                    mixInto(busPcm, scratch, got, 1f, false);   // sum, saturating
                    read = Math.max(read, got);
                } else if (now - source.lastWriteNanos > KEYED_IDLE_NANOS) {
                    sources.remove(entry.getKey(), source);
                }
            }
            CopyOnWriteArrayList<Consumer<byte[]>> taps = busSinks.get(kind);
            if (!taps.isEmpty()) {
                byte[] contribution = new byte[byteCount];
                if (read > 0 && gain > 0) {
                    mixInto(contribution, busPcm, read, gain, true);
                }
                for (Consumer<byte[]> tap : taps) {
                    try {
                        tap.accept(contribution);
                    } catch (RuntimeException e) {
                        StreamAbleLog.AUDIO.warn("Audio bus sink rejected a block", e);
                    }
                }
            }
            if (read <= 0 || gain <= 0) {
                continue;
            }
            float peak = mixInto(mixed, busPcm, read, gain, false);
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

    /** Trims a queue to its latency bound, then reads one block from it. */
    private int drain(AudioBus.Kind kind, PcmRingBuffer buffer, byte[] target, int byteCount) {
        int excess = buffer.size() - byteCount - maxBacklogBytes(kind);
        if (excess > 0) {
            excess -= excess % FRAME_BYTES;
            trimmedBytes.get(kind).addAndGet(buffer.discard(excess));
        }
        return buffer.read(target, byteCount);
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
    /**
     * Submits audio from one of several simultaneous sources on a bus (a
     * speaking player, a browser source). Each key is queued separately and the
     * keys are summed at mix time.
     */
    public void submit(AudioBus.Kind kind, Object sourceKey, byte[] pcm, int length) {
        if (!active || length <= 0) {
            return;
        }
        keyed.get(kind).computeIfAbsent(sourceKey, k -> new KeyedSource()).write(pcm, length, System.nanoTime());
    }

    /** Number of keyed sources currently known on a bus (diagnostics, tests). */
    public int keyedSourceCount(AudioBus.Kind kind) {
        return keyed.get(kind).size();
    }

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

    /** One keyed stream on a bus. */
    private static final class KeyedSource {
        final PcmRingBuffer buffer = new PcmRingBuffer(SAMPLE_RATE * FRAME_BYTES);
        volatile long lastWriteNanos;

        void write(byte[] pcm, int length, long now) {
            buffer.write(pcm, length);
            lastWriteNanos = now;
        }
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

        synchronized int size() {
            return size;
        }

        /** Drops the oldest {@code count} bytes; returns how many were dropped. */
        synchronized int discard(int count) {
            int dropped = Math.min(count, size);
            System.arraycopy(data, dropped, data, 0, size - dropped);
            size -= dropped;
            return dropped;
        }
    }
}
