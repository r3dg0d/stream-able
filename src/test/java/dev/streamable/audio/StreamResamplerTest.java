package dev.streamable.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamResamplerTest {

    @Test
    void resamples44100ToTheMixerRateWithoutLosingTime() {
        StreamResampler resampler = new StreamResampler();
        int total = 0;
        for (int chunk = 0; chunk < 100; chunk++) {        // 100 x 441 frames = 1 s at 44.1 kHz
            short[] samples = new short[441 * 2];
            for (int f = 0; f < 441; f++) {
                short v = (short) (8000 * Math.sin(2 * Math.PI * 1000 * (chunk * 441 + f) / 44100.0));
                samples[2 * f] = v;
                samples[2 * f + 1] = v;
            }
            total += resampler.toMixerFormat("page", samples, 2, 44100).length / 4;
        }
        assertTrue(Math.abs(total - 48000) < 200, "about one second at 48 kHz, got " + total + " frames");
    }

    @Test
    void mixerRateStreamsPassStraightThrough() {
        StreamResampler resampler = new StreamResampler();
        short[] samples = {100, -100, 200, -200};
        assertEquals(8, resampler.toMixerFormat("x", samples, 2, 48000).length);
    }
}
