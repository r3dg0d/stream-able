package dev.streamable.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioMixerTimingTest {

    private static final long MS = 1_000_000L;
    private static final int FRAME_BYTES = AudioMixer.CHANNELS * AudioMixer.BYTES_PER_SAMPLE;

    @Test
    void emitsExactlyTheElapsedSampleCountIncludingAfterAStall() {
        AudioMixer mixer = new AudioMixer();
        AtomicLong bytes = new AtomicLong();
        mixer.addSink(block -> bytes.addAndGet(block.length));
        mixer.startClockForTesting(0);
        for (long t = 20; t <= 1000; t += 20) {
            mixer.emitDue(t * MS);
        }
        // A 3.5 second stall of the mixer thread: all of it must be emitted.
        mixer.emitDue(4_500 * MS);
        assertEquals(48_000L * 4_500 / 1000, mixer.framesEmitted());
        assertEquals(mixer.framesEmitted() * FRAME_BYTES, bytes.get());
    }

    @Test
    void longSessionHasNoDrift() {
        AudioMixer mixer = new AudioMixer();
        mixer.startClockForTesting(0);
        java.util.Random jitter = new java.util.Random(3);
        long t = 0;
        while (t < 30L * 60 * 1000 * MS) {                 // 30 minutes
            t += (15 + jitter.nextInt(12)) * MS;           // irregular 15..26 ms ticks
            mixer.emitDue(t);
        }
        assertEquals(t * 48_000L / 1_000_000_000L, mixer.framesEmitted());
    }

    @Test
    void microphoneBacklogIsBoundedWithoutTouchingTheClock() {
        AudioMixer mixer = new AudioMixer();
        mixer.startClockForTesting(0);
        byte[] tenMs = new byte[480 * FRAME_BYTES];
        // A microphone that runs 10% fast: 22 ms of audio per 20 ms tick.
        for (int tick = 1; tick <= 500; tick++) {
            mixer.submit(AudioBus.Kind.MICROPHONE, tenMs, tenMs.length);
            mixer.submit(AudioBus.Kind.MICROPHONE, tenMs, tenMs.length);
            mixer.submit(AudioBus.Kind.MICROPHONE, java.util.Arrays.copyOf(tenMs, 96 * FRAME_BYTES), 96 * FRAME_BYTES);
            mixer.emitDue(tick * 20L * MS);
        }
        assertTrue(mixer.backlogMillis(AudioBus.Kind.MICROPHONE) <= 81,
                "backlog " + mixer.backlogMillis(AudioBus.Kind.MICROPHONE) + " ms");
        assertTrue(mixer.trimmedBytes(AudioBus.Kind.MICROPHONE) > 0);
        assertEquals(48_000L * 10, mixer.framesEmitted(), "clock unaffected by trimming");
    }

    @Test
    void busSinkReceivesAlignedBlocksIncludingSilence() {
        AudioMixer mixer = new AudioMixer();
        List<Integer> sizes = new ArrayList<>();
        AtomicLong programBytes = new AtomicLong();
        mixer.addSink(block -> programBytes.addAndGet(block.length));
        mixer.addBusSink(AudioBus.Kind.MICROPHONE, block -> sizes.add(block.length));
        mixer.startClockForTesting(0);
        for (long t = 20; t <= 200; t += 20) {
            mixer.emitDue(t * MS);
        }
        assertEquals(programBytes.get(), sizes.stream().mapToLong(Integer::longValue).sum(),
                "the separate track has exactly the program's length");
    }
}
