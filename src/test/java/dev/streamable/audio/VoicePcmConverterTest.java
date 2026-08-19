package dev.streamable.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VoicePcmConverterTest {

    private static short sampleAt(byte[] pcm, int frame, int channel) {
        int offset = frame * 4 + channel * 2;
        return (short) ((pcm[offset] & 0xFF) | (pcm[offset + 1] << 8));
    }

    @Test
    @DisplayName("mono voice is duplicated into both stereo channels")
    void monoIsDuplicated() {
        short[] mono = {100, -200, 300};
        byte[] out = VoicePcmConverter.toMixerFormat(mono, 1, AudioMixer.SAMPLE_RATE);
        assertEquals(3 * 4, out.length);
        for (int frame = 0; frame < 3; frame++) {
            assertEquals(mono[frame], sampleAt(out, frame, 0), "left");
            assertEquals(mono[frame], sampleAt(out, frame, 1), "right");
        }
    }

    @Test
    void stereoIsPassedThroughUnchanged() {
        short[] stereo = {10, 20, 30, 40};
        byte[] out = VoicePcmConverter.toMixerFormat(stereo, 2, AudioMixer.SAMPLE_RATE);
        assertEquals(2 * 4, out.length);
        assertEquals(10, sampleAt(out, 0, 0));
        assertEquals(20, sampleAt(out, 0, 1));
        assertEquals(30, sampleAt(out, 1, 0));
        assertEquals(40, sampleAt(out, 1, 1));
    }

    @Test
    void outputIsLittleEndian() {
        byte[] out = VoicePcmConverter.toMixerFormat(new short[]{0x1234}, 1, AudioMixer.SAMPLE_RATE);
        assertEquals(0x34, out[0] & 0xFF, "low byte first");
        assertEquals(0x12, out[1] & 0xFF);
    }

    @Test
    @DisplayName("a lower source rate is resampled up rather than pitch-shifted")
    void resamplesToMixerRate() {
        short[] mono = new short[24_000];               // one second at 24 kHz
        byte[] out = VoicePcmConverter.toMixerFormat(mono, 1, 24_000);
        int frames = out.length / 4;
        assertEquals(AudioMixer.SAMPLE_RATE, frames, "one second must stay one second");
    }

    @Test
    void preservesExtremeValuesWithoutWrapping() {
        short[] mono = {Short.MAX_VALUE, Short.MIN_VALUE};
        byte[] out = VoicePcmConverter.toMixerFormat(mono, 1, AudioMixer.SAMPLE_RATE);
        assertEquals(Short.MAX_VALUE, sampleAt(out, 0, 0));
        assertEquals(Short.MIN_VALUE, sampleAt(out, 1, 0));
    }

    @Test
    void handlesEmptyAndNullInput() {
        assertEquals(0, VoicePcmConverter.toMixerFormat(null, 1, 48_000).length);
        assertEquals(0, VoicePcmConverter.toMixerFormat(new short[0], 1, 48_000).length);
        assertEquals(0, VoicePcmConverter.toMixerFormat(new short[1], 2, 48_000).length);
    }
}
