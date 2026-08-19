package dev.streamable.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the PCM unpacking. Endianness and gain errors here are silent - the
 * stream just sounds like noise or nothing - so they are worth pinning down.
 */
class MicrophoneCaptureTest {

    @Test
    void unpacksLittleEndianSamples() {
        byte[] buffer = {0x34, 0x12, (byte) 0xFF, 0x7F};
        short[] samples = MicrophoneCapture.toSamples(buffer, 4, false, 1.0f);
        assertArrayEquals(new short[]{0x1234, 0x7FFF}, samples);
    }

    @Test
    void unpacksBigEndianSamples() {
        byte[] buffer = {0x12, 0x34, 0x7F, (byte) 0xFF};
        short[] samples = MicrophoneCapture.toSamples(buffer, 4, true, 1.0f);
        assertArrayEquals(new short[]{0x1234, 0x7FFF}, samples);
    }

    @Test
    void preservesNegativeSamples() {
        byte[] buffer = {0x00, (byte) 0x80};          // -32768 little-endian
        assertEquals(Short.MIN_VALUE, MicrophoneCapture.toSamples(buffer, 2, false, 1.0f)[0]);
    }

    @Test
    void appliesGain() {
        byte[] buffer = {0x10, 0x00};                  // 16
        assertEquals(32, MicrophoneCapture.toSamples(buffer, 2, false, 2.0f)[0]);
        assertEquals(8, MicrophoneCapture.toSamples(buffer, 2, false, 0.5f)[0]);
    }

    @Test
    @DisplayName("gain saturates rather than wrapping to the opposite sign")
    void gainSaturates() {
        byte[] loud = {(byte) 0xFF, 0x7F};             // 32767
        assertEquals(Short.MAX_VALUE, MicrophoneCapture.toSamples(loud, 2, false, 4.0f)[0]);
        byte[] quiet = {0x00, (byte) 0x80};            // -32768
        assertEquals(Short.MIN_VALUE, MicrophoneCapture.toSamples(quiet, 2, false, 4.0f)[0]);
    }

    @Test
    void readsOnlyTheRequestedLength() {
        byte[] buffer = new byte[64];
        buffer[0] = 0x10;
        assertEquals(2, MicrophoneCapture.toSamples(buffer, 4, false, 1.0f).length);
    }

    @Test
    void deviceEnumerationNeverThrows() {
        assertNotNull(MicrophoneCapture.availableDevices());
    }
}
