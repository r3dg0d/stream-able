package dev.streamable.audio.mic;

import dev.streamable.audio.AudioBus;
import dev.streamable.audio.AudioMixer;
import dev.streamable.audio.ai.NoiseCancellationManager;
import dev.streamable.audio.ai.NoiseCancellationStage;
import dev.streamable.config.MicrophoneSettings;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrophoneProcessorTest {

    private static MicrophoneProcessor processor(MicrophoneSettings settings) {
        NoiseCancellationStage stage = new NoiseCancellationStage();
        NoiseCancellationManager manager = new NoiseCancellationManager(stage, (b, p) -> {
            throw new IllegalStateException("no AI in this test");
        });
        return new MicrophoneProcessor(new AudioMixer(), settings, manager);
    }

    private static MicrophoneSettings settings() {
        MicrophoneSettings s = new MicrophoneSettings();
        s.validate();
        return s;
    }

    @Test
    void monoIsCentredInBothChannels() {
        byte[] pcm = MicrophoneProcessor.toCentredStereo(new float[]{0.5f, -0.25f}, 2);
        short left = (short) ((pcm[0] & 0xFF) | (pcm[1] << 8));
        short right = (short) ((pcm[2] & 0xFF) | (pcm[3] << 8));
        assertEquals(left, right, "a mono microphone must never be left-only");
        assertEquals(16384, left, 1);
    }

    @Test
    void queueIsBoundedAndCountsDrops() {
        MicrophoneSettings s = settings();
        MicrophoneProcessor processor = processor(s);
        processor.start();
        processor.stop();                       // worker gone, but accept a burst while "running"
        // Re-arm without a worker by starting and immediately filling faster than it can drain.
        processor.start();
        float[] second = new float[48_000 * 5];   // 5 s arriving at once
        processor.submit(second, 0, second.length);
        MicrophoneProcessor.Stats stats = processor.stats();
        assertTrue(stats.backlogBlocks() <= MicrophoneProcessor.QUEUE_BLOCKS);
        processor.stop();
        assertTrue(processor.stats().droppedBlocks() > 0, "a burst beyond the bound is dropped, not queued");
    }

    @Test
    void processedAudioReachesTheMixerOnlyWhenAsked() throws Exception {
        MicrophoneSettings s = settings();
        AudioMixer mixer = new AudioMixer();
        NoiseCancellationStage stage = new NoiseCancellationStage();
        MicrophoneProcessor processor = new MicrophoneProcessor(mixer, s,
                new NoiseCancellationManager(stage, (b, p) -> { throw new IllegalStateException(); }));
        AtomicInteger blocks = new AtomicInteger();
        processor.addProcessedListener((samples, length) -> blocks.incrementAndGet());
        mixer.start();
        processor.setSendToMixer(false);
        processor.start();
        float[] tone = new float[4800];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (float) (0.3 * Math.sin(i * 0.05));
        }
        processor.submit(tone, 0, tone.length);
        Thread.sleep(200);
        assertEquals(10, blocks.get(), "ten 10 ms blocks processed");
        assertEquals(0, mixer.backlogMillis(AudioBus.Kind.MICROPHONE), 0.001);
        processor.setSendToMixer(true);
        processor.submit(tone, 0, tone.length);
        Thread.sleep(200);
        assertTrue(mixer.bus(AudioBus.Kind.MICROPHONE).samplesReceived() > 0 || mixer.backlogMillis(AudioBus.Kind.MICROPHONE) > 0);
        processor.stop();
        mixer.stop();
    }

    @Test
    void muteAndPushToTalkGating() {
        MicrophoneSettings s = settings();
        MicrophoneProcessor processor = processor(s);
        assertTrue(processor.isOpenNow());
        s.muted = true;
        assertTrue(!processor.isOpenNow());
        s.muted = false;
        s.pushToTalk = true;
        s.pushToTalkReleaseMs = 0;
        assertTrue(!processor.isOpenNow(), "push-to-talk closed until held");
        processor.setPushToTalkHeld(true);
        assertTrue(processor.isOpenNow());
        processor.setPushToTalkHeld(false);
        assertTrue(!processor.isOpenNow());
        s.pushToTalk = false;
        processor.setPushToMuteHeld(true);
        assertTrue(!processor.isOpenNow(), "push-to-mute silences while held");
        processor.setPushToMuteHeld(false);
        assertTrue(processor.isOpenNow());
    }

    @Test
    void pushToTalkReleaseTailKeepsTheLastWord() {
        MicrophoneSettings s = settings();
        s.pushToTalk = true;
        s.pushToTalkReleaseMs = 500;
        MicrophoneProcessor processor = processor(s);
        processor.setPushToTalkHeld(true);
        processor.setPushToTalkHeld(false);
        assertTrue(processor.isOpenNow(), "still open during the release tail");
    }

    @Test
    void autoChannelUsesTheLiveChannelOfAnInterface() {
        assertEquals(MicrophoneSettings.InputChannel.LEFT, ChannelSelector.choose(1.0, 1e-6));
        assertEquals(MicrophoneSettings.InputChannel.RIGHT, ChannelSelector.choose(1e-6, 1.0));
        assertEquals(MicrophoneSettings.InputChannel.MIX, ChannelSelector.choose(1.0, 0.5));
        ChannelSelector selector = new ChannelSelector();
        short[] leftOnly = new short[960];
        for (int i = 0; i < 480; i++) {
            leftOnly[2 * i] = 10_000;
        }
        float[] mono = new float[480];
        for (int k = 0; k < 20; k++) {
            selector.toMono(leftOnly, 480, 2, MicrophoneSettings.InputChannel.AUTO, mono);
        }
        assertEquals(10_000 / 32768f, mono[0], 1e-6, "no -6 dB loss from averaging with a dead channel");
    }
}
