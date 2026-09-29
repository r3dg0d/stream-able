package dev.streamable.browser;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps the shipped browser smoke page aligned with issue #1 fixtures. */
class BrowserTestPageContentTest {

    @Test
    void shipsIframeAndSpeechSynthesisFixtures() throws IOException {
        try (InputStream in = BrowserTestPage.class.getResourceAsStream(
                "/assets/streamable/browser/test-page.html")) {
            assertNotNull(in, "test-page.html missing from resources");
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(html.contains("speechSynthesis"), "TTS smoke control");
            assertTrue(html.contains("audioFrame") || html.contains("iframe"), "iframe audio fixture");
            assertTrue(html.contains("tapped when capture is on")
                            || html.contains("Browser Sources"),
                    "stale local-only claim must not remain");
            assertTrue(!html.contains("will NOT appear in"),
                    "stale claim that Web Audio never reaches the broadcast");
        }
    }
}
