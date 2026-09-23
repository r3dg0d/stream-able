package dev.streamable.audio.dsp;

import dev.streamable.config.MicrophoneSettings;
import org.junit.jupiter.api.Test;

import static dev.streamable.audio.dsp.Signals.SR;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrophoneChainTest {

    private static MicrophoneSettings allOff() {
        MicrophoneSettings s = new MicrophoneSettings();
        s.highPass.enabled = false;
        s.gate.enabled = false;
        s.eq.enabled = false;
        s.deEsser.enabled = false;
        s.compressor.enabled = false;
        s.agc.enabled = false;
        s.limiter.enabled = false;
        s.validate();
        return s;
    }

    @Test
    void masterBypassIsBitExact() {
        MicrophoneSettings s = new MicrophoneSettings();
        s.processingEnabled = false;
        s.validate();
        MicrophoneChain chain = new MicrophoneChain(new DspStagesTest.PassThroughStage());
        chain.configure(s);
        float[] in = Signals.noise(0.5, 4800, 2);
        float[] data = in.clone();
        chain.process(data, 0, data.length);
        assertArrayEquals(in, data);
    }

    @Test
    void everythingOffOnlyRemovesDc() {
        MicrophoneChain chain = new MicrophoneChain(new DspStagesTest.PassThroughStage());
        chain.configure(allOff());
        float[] voice = Signals.sine(440, 0.3, SR);
        float[] data = voice.clone();
        chain.process(data, 0, data.length);
        assertEquals(Signals.rmsDb(voice, SR / 2, SR), Signals.rmsDb(data, SR / 2, SR), 0.05,
                "a clean chain leaves the voice's level unchanged");
        assertEquals(0, chain.latencySamples());
    }

    @Test
    void latencyIsReportedAndSmall() {
        MicrophoneSettings s = new MicrophoneSettings();
        s.validate();
        MicrophoneChain chain = new MicrophoneChain(new DspStagesTest.PassThroughStage());
        chain.configure(s);
        assertTrue(chain.latencyMillis() > 0 && chain.latencyMillis() < 6,
                "gate look-ahead + limiter look-ahead only: " + chain.latencyMillis() + " ms");
    }

    @Test
    void stageBypassForAbComparison() {
        MicrophoneSettings s = allOff();
        s.compressor.enabled = true;
        s.compressor.thresholdDb = -40;
        s.compressor.ratio = 10;
        s.compressor.autoMakeup = false;
        MicrophoneChain chain = new MicrophoneChain(new DspStagesTest.PassThroughStage());
        chain.configure(s);
        float[] loud = Signals.sine(440, 0.5, SR);
        float[] compressed = loud.clone();
        chain.process(compressed, 0, compressed.length);
        chain.reset();
        chain.setBypassed("compressor", true);
        float[] bypassed = loud.clone();
        chain.process(bypassed, 0, bypassed.length);
        assertTrue(Signals.rmsDb(bypassed, SR / 2, SR) > Signals.rmsDb(compressed, SR / 2, SR) + 10);
    }

    @Test
    void metersReportCalibratedLevels() {
        MicrophoneChain chain = new MicrophoneChain(new DspStagesTest.PassThroughStage());
        chain.configure(allOff());
        float[] data = Signals.sine(1000, Db.toLinear(-12), SR);
        for (int i = 0; i < data.length; i += 480) {
            chain.process(data, i, 480);
        }
        LevelMeter.Reading reading = chain.inputLevel();
        assertEquals(-12, reading.peakDb(), 0.1);
        assertEquals(-15.01, reading.rmsDb(), 0.2, "sine RMS is 3 dB below its peak");
        assertTrue(!reading.clipping());
    }

    @Test
    void clippingIsDetected() {
        LevelMeter meter = new LevelMeter(SR);
        float[] clipped = Signals.constant(1.0, 480);
        meter.process(clipped, 0, 480);
        assertTrue(meter.reading().clipping());
        assertEquals(480, meter.reading().clippedSamples());
    }

    @Test
    void settingsRevisionTriggersReconfigure() {
        MicrophoneSettings s = allOff();
        MicrophoneChain chain = new MicrophoneChain(new DspStagesTest.PassThroughStage());
        chain.configureIfChanged(s);
        s.inputGainDb = 12;
        chain.configureIfChanged(s);          // no revision bump: ignored
        float[] tone = Signals.sine(440, 0.01, SR / 2);
        float[] data = tone.clone();
        chain.process(data, 0, data.length);
        assertEquals(Signals.rmsDb(tone, SR / 4, SR / 2), Signals.rmsDb(data, SR / 4, SR / 2), 0.1);
        s.touch();
        chain.configureIfChanged(s);
        data = tone.clone();
        chain.process(data, 0, data.length);
        assertEquals(Signals.rmsDb(tone, SR / 4, SR / 2) + 12, Signals.rmsDb(data, SR / 4, SR / 2), 0.2);
    }
}
