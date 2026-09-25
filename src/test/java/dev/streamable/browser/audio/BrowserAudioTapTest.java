package dev.streamable.browser.audio;

import dev.streamable.source.BrowserAudioMode;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserAudioTapTest {

    private static String request(int stream, int rate, int channels, byte[] pcm) {
        return "sa-pcm:" + stream + ":" + rate + ":" + channels + ":" + Base64.getEncoder().encodeToString(pcm);
    }

    @Test
    void decodesLittleEndianStereoChunks() {
        byte[] pcm = {0x01, 0x00, (byte) 0xFF, (byte) 0xFF, 0x00, (byte) 0x80, (byte) 0xFF, 0x7F};
        BrowserAudioTap.Chunk chunk = BrowserAudioTap.parse(request(3, 48000, 2, pcm));
        assertNotNull(chunk);
        assertEquals(3, chunk.stream());
        assertEquals(48000, chunk.sampleRate());
        assertArrayEquals(new short[]{1, -1, Short.MIN_VALUE, Short.MAX_VALUE}, chunk.samples());
    }

    @Test
    void rejectsAnythingMalformedOrOutOfRange() {
        byte[] four = new byte[4];
        assertNull(BrowserAudioTap.parse(null));
        assertNull(BrowserAudioTap.parse("hello"));
        assertNull(BrowserAudioTap.parse("sa-pcm:1:48000:2"));
        assertNull(BrowserAudioTap.parse(request(0, 48000, 2, four)), "stream ids start at 1");
        assertNull(BrowserAudioTap.parse(request(1, 1000, 2, four)), "implausible rate");
        assertNull(BrowserAudioTap.parse(request(1, 48000, 3, four)), "only mono or stereo");
        assertNull(BrowserAudioTap.parse(request(1, 48000, 2, new byte[6])), "partial frame");
        assertNull(BrowserAudioTap.parse("sa-pcm:1:48000:2:!!!not-base64"));
        assertNull(BrowserAudioTap.parse(request(1, 48000, 2, new byte[BrowserAudioTap.MAX_SAMPLES * 2 + 4])),
                "oversized chunk");
    }

    @Test
    void scriptCarriesTheSourceSettings() {
        String stream = BrowserAudioTap.script(BrowserAudioMode.STREAM_ONLY, 0.5f);
        assertTrue(stream.contains("{\"monitor\":false,\"capture\":true,\"volume\":0.500}"), "stream only");
        assertFalse(stream.contains("__SA_CONFIG__"));
        assertTrue(BrowserAudioTap.script(BrowserAudioMode.OFF, 1f).contains("\"monitor\":false,\"capture\":false"));
        assertTrue(BrowserAudioTap.script(BrowserAudioMode.MONITOR_AND_STREAM, 1f).contains("\"monitor\":true,\"capture\":true"));
        assertTrue(stream.contains(BrowserAudioTap.QUERY_FUNCTION), "the script uses the router's function");
    }
}
