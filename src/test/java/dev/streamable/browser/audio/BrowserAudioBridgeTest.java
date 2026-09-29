package dev.streamable.browser.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserAudioBridgeTest {

    @Test
    void limitationReasonCoversFramesAndSpeechSynthesis() {
        String reason = BrowserAudioBridge.limitationReason();
        assertTrue(reason.contains("iframe") || reason.contains("frame"), reason);
        assertTrue(reason.toLowerCase().contains("speechsynthesis")
                || reason.contains("speechSynthesis")
                || reason.contains("Speech"), reason);
        assertFalse(reason.contains("audio inside iframes are only heard locally"),
                "stale claim that iframe audio is never tapped");
    }
}
