package dev.streamable.audio.mic;

import dev.streamable.StreamAbleLog;
import dev.streamable.audio.AudioBus;
import dev.streamable.audio.AudioMixer;
import dev.streamable.audio.ai.NoiseCancellationManager;
import dev.streamable.audio.ai.NoiseCancellationStage;
import dev.streamable.audio.dsp.Db;
import dev.streamable.audio.dsp.MicrophoneChain;
import dev.streamable.config.MicrophoneSettings;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The real-time microphone worker: capture blocks in, processed voice out to
 * the program mixer.
 *
 * <pre>
 *   capture thread --(bounded queue of 10 ms blocks)--&gt; DSP worker
 *        DSP worker: chain (HPF, AI, gate, EQ, de-esser, comp, AGC, limiter)
 *                    -&gt; mute / push-to-talk ramps -&gt; centred stereo -&gt; mixer bus
 * </pre>
 *
 * <h2>Bounded, never unbounded</h2>
 * <ul>
 *   <li>The queue holds at most {@value #QUEUE_BLOCKS} blocks. If the worker
 *       falls more than {@value #OVERLOAD_BLOCKS} blocks behind, AI inference is
 *       skipped (the stage passes the time-aligned dry voice) until it catches
 *       up; beyond {@value #DROP_BLOCKS} blocks the stalest blocks are dropped.
 *       Latency therefore never accumulates, and the microphone keeps
 *       working.</li>
 *   <li>Buffers are pooled; the hot path allocates only the small PCM block
 *       handed to the mixer.</li>
 * </ul>
 *
 * <p>The mixer keeps its own wall clock and trims this bus's backlog, so
 * nothing here can re-clock the program stream or cause long-session drift.</p>
 */
public final class MicrophoneProcessor implements AutoCloseable {

    public static final int BLOCK = 480;                 // 10 ms at 48 kHz
    static final int QUEUE_BLOCKS = 64;
    static final int OVERLOAD_BLOCKS = 3;
    static final int DROP_BLOCKS = 25;

    private final AudioMixer mixer;
    private final MicrophoneSettings settings;
    private final NoiseCancellationStage noiseStage;
    private final NoiseCancellationManager noiseManager;
    private final MicrophoneChain chain;
    private final ArrayBlockingQueue<float[]> queue = new ArrayBlockingQueue<>(QUEUE_BLOCKS);
    private final ArrayBlockingQueue<float[]> pool = new ArrayBlockingQueue<>(QUEUE_BLOCKS + 8);
    private final CopyOnWriteArrayList<BlockListener> rawListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<BlockListener> processedListeners = new CopyOnWriteArrayList<>();

    // Producer-side accumulator (capture thread).
    private final float[] accumulator = new float[BLOCK];
    private int accumulated;

    private volatile boolean running;
    private Thread worker;
    private volatile boolean sendToMixer = true;

    // Gate for mute / push-to-talk / push-to-mute.
    private volatile boolean pushToTalkHeld;
    private volatile boolean pushToMuteHeld;
    private long pushToTalkReleasedAt;
    private double gateGain = 1.0;
    private final double gateRamp = Db.coefficient(5, MicrophoneChain.SAMPLE_RATE);

    // Diagnostics.
    private final AtomicLong droppedBlocks = new AtomicLong();
    private final AtomicLong overrunEvents = new AtomicLong();
    private final AtomicLong underruns = new AtomicLong();
    private final AtomicLong processedBlocks = new AtomicLong();
    private volatile double dspMillis;
    private volatile double dspPeakMillis;
    private volatile int backlog;
    private boolean overloaded;
    private long lastSupervise;

    public MicrophoneProcessor(AudioMixer mixer, MicrophoneSettings settings, NoiseCancellationManager noiseManager) {
        this.mixer = mixer;
        this.settings = settings;
        this.noiseManager = noiseManager;
        this.noiseStage = noiseManager.stage();
        this.chain = new MicrophoneChain(noiseStage);
        for (int i = 0; i < QUEUE_BLOCKS + 8; i++) {
            pool.offer(new float[BLOCK]);
        }
    }

    public MicrophoneChain chain() {
        return chain;
    }

    public NoiseCancellationStage noiseStage() {
        return noiseStage;
    }

    public void addRawListener(BlockListener listener) {
        rawListeners.add(listener);
    }

    public void removeRawListener(BlockListener listener) {
        rawListeners.remove(listener);
    }

    public void addProcessedListener(BlockListener listener) {
        processedListeners.add(listener);
    }

    public void removeProcessedListener(BlockListener listener) {
        processedListeners.remove(listener);
    }

    /** Whether processed audio goes to the program mix (off while only metering/testing). */
    public void setSendToMixer(boolean value) {
        sendToMixer = value;
    }

    public void setPushToTalkHeld(boolean held) {
        if (pushToTalkHeld && !held) {
            pushToTalkReleasedAt = System.nanoTime();
        }
        pushToTalkHeld = held;
    }

    public void setPushToMuteHeld(boolean held) {
        pushToMuteHeld = held;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        queue.clear();
        accumulated = 0;
        chain.configure(settings);
        chain.reset();
        worker = Thread.ofPlatform().name("stream-able-mic-dsp").daemon(true).start(this::runWorker);
        worker.setPriority(Thread.MAX_PRIORITY - 1);
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        queue.clear();
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Accepts 48 kHz mono samples from a capture thread. Never blocks: when the
     * queue is full the block is dropped and counted.
     */
    public synchronized void submit(float[] samples, int offset, int length) {
        if (!running) {
            return;
        }
        int i = offset;
        int end = offset + length;
        while (i < end) {
            int take = Math.min(BLOCK - accumulated, end - i);
            System.arraycopy(samples, i, accumulator, accumulated, take);
            accumulated += take;
            i += take;
            if (accumulated == BLOCK) {
                float[] block = pool.poll();
                if (block == null) {
                    droppedBlocks.incrementAndGet();
                } else {
                    System.arraycopy(accumulator, 0, block, 0, BLOCK);
                    if (!queue.offer(block)) {
                        pool.offer(block);
                        droppedBlocks.incrementAndGet();
                    }
                }
                accumulated = 0;
            }
        }
    }

    private void runWorker() {
        long lastBlockAt = System.nanoTime();
        while (running) {
            float[] block;
            try {
                block = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            if (block == null) {
                if (now - lastBlockAt > 60_000_000L) {
                    underruns.incrementAndGet();
                }
                continue;
            }
            lastBlockAt = now;
            try {
                handleBacklog();
                processBlock(block);
            } catch (RuntimeException e) {
                StreamAbleLog.AUDIO.warn("Microphone processing failed for one block", e);
            } finally {
                pool.offer(block);
            }
            if (now - lastSupervise > 1_000_000_000L) {
                lastSupervise = now;
                noiseManager.supervise(settings);
            }
        }
    }

    /** Overload policy: skip AI when behind, drop the stalest audio when far behind. */
    private void handleBacklog() {
        int waiting = queue.size();
        backlog = waiting;
        if (waiting > DROP_BLOCKS) {
            int drop = waiting - OVERLOAD_BLOCKS;
            for (int i = 0; i < drop; i++) {
                float[] stale = queue.poll();
                if (stale == null) {
                    break;
                }
                pool.offer(stale);
                droppedBlocks.incrementAndGet();
            }
        }
        if (!overloaded && waiting > OVERLOAD_BLOCKS) {
            overloaded = true;
            overrunEvents.incrementAndGet();
            noiseStage.setOverloaded(true);
        } else if (overloaded && waiting <= 1) {
            overloaded = false;
            noiseStage.setOverloaded(false);
        }
    }

    private void processBlock(float[] block) {
        long start = System.nanoTime();
        chain.configureIfChanged(settings);
        for (BlockListener listener : rawListeners) {
            listener.onBlock(block, BLOCK);
        }
        chain.process(block, 0, BLOCK);
        applyGate(block);
        for (BlockListener listener : processedListeners) {
            listener.onBlock(block, BLOCK);
        }
        if (sendToMixer && mixer.isActive()) {
            byte[] pcm = toCentredStereo(block, BLOCK);
            mixer.submit(AudioBus.Kind.MICROPHONE, pcm, pcm.length);
        }
        double millis = (System.nanoTime() - start) / 1e6;
        dspMillis = dspMillis * 0.95 + millis * 0.05;
        dspPeakMillis = Math.max(dspPeakMillis * 0.999, millis);
        processedBlocks.incrementAndGet();
    }

    /** Whether the microphone should currently be heard. */
    boolean isOpenNow() {
        if (settings.muted || pushToMuteHeld) {
            return false;
        }
        if (settings.pushToTalk) {
            if (pushToTalkHeld) {
                return true;
            }
            long sinceRelease = System.nanoTime() - pushToTalkReleasedAt;
            return pushToTalkReleasedAt != 0 && sinceRelease < (long) (settings.pushToTalkReleaseMs * 1_000_000L);
        }
        return true;
    }

    private void applyGate(float[] block) {
        double target = isOpenNow() ? 1.0 : 0.0;
        if (target == 1.0 && gateGain == 1.0) {
            return;
        }
        for (int i = 0; i < BLOCK; i++) {
            gateGain = target + (gateGain - target) * gateRamp;
            block[i] *= (float) gateGain;
        }
        if (Math.abs(gateGain - target) < 1e-4) {
            gateGain = target;
        }
    }

    /** Mono to 16-bit stereo, identical in both channels: centred, never left-only. */
    static byte[] toCentredStereo(float[] mono, int length) {
        byte[] pcm = new byte[length * 4];
        for (int i = 0; i < length; i++) {
            int value = (int) Math.round(Math.clamp(mono[i], -1f, 1f) * 32767.0);
            byte lo = (byte) value;
            byte hi = (byte) (value >> 8);
            int o = i * 4;
            pcm[o] = lo;
            pcm[o + 1] = hi;
            pcm[o + 2] = lo;
            pcm[o + 3] = hi;
        }
        return pcm;
    }

    // ---- diagnostics ----------------------------------------------------------

    /** Snapshot for the Advanced Audio page. */
    public record Stats(double dspMillis, double dspPeakMillis, double chainLatencyMillis, int backlogBlocks,
                        long droppedBlocks, long overrunEvents, long underruns, long processedBlocks,
                        boolean overloaded) {
        /** DSP time per block divided by the block's duration. */
        public double realTimeFactor() {
            return dspMillis / 10.0;
        }
    }

    public Stats stats() {
        return new Stats(dspMillis, dspPeakMillis, chain.latencyMillis(), backlog, droppedBlocks.get(),
                overrunEvents.get(), underruns.get(), processedBlocks.get(), overloaded);
    }

    @Override
    public void close() {
        stop();
    }
}
