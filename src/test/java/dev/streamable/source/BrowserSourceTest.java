package dev.streamable.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Page volume is stored by {@link BrowserSource#setAudioVolume}, which clamps
 * to 0–1. The Studio slider uses that same range.
 */
class BrowserSourceTest {

    @Test
    void pageVolumeClampsToTheStoredRange() {
        BrowserSource source = BrowserSource.create("Page", "about:blank", 0, 0, 100, 100);

        source.setAudioVolume(-0.5f);
        assertEquals(0f, source.audioVolume(), 0f);

        source.setAudioVolume(0f);
        assertEquals(0f, source.audioVolume(), 0f);

        source.setAudioVolume(0.5f);
        assertEquals(0.5f, source.audioVolume(), 0f);

        source.setAudioVolume(1f);
        assertEquals(1f, source.audioVolume(), 0f);

        source.setAudioVolume(2f);
        assertEquals(1f, source.audioVolume(), 0f);
    }
}
