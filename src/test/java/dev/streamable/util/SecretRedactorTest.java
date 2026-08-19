package dev.streamable.util;

import dev.streamable.streaming.StreamingCredentials;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretRedactorTest {

    private static final String KEY = "live_123456789_abcdefGHIJKLmnop";

    @Test
    void removesKnownSecretFromArbitraryText() {
        String text = "Failed to open output: rtmp://live.twitch.tv/app/" + KEY + " (Connection refused)";
        String redacted = SecretRedactor.redact(text, KEY);
        assertFalse(redacted.contains(KEY));
        assertTrue(redacted.contains(SecretRedactor.MASK));
    }

    @Test
    void redactsIngestUrlTailEvenWithoutKnowingTheKey() {
        String text = "rtmps://a.rtmp.youtube.com/live2/abcd-efgh-ijkl-mnop-qrst";
        String redacted = SecretRedactor.redact(text);
        assertFalse(redacted.contains("abcd-efgh-ijkl-mnop-qrst"));
        assertTrue(redacted.startsWith("rtmps://a.rtmp.youtube.com/live2/"));
    }

    @Test
    void redactsQueryStringCredentials() {
        assertFalse(SecretRedactor.redact("https://host/x?streamkey=sup3rSecret&a=1").contains("sup3rSecret"));
        assertFalse(SecretRedactor.redact("https://host/x?token=abc123def").contains("abc123def"));
    }

    @Test
    void recognisesTwitchAndYoutubeKeyShapes() {
        assertFalse(SecretRedactor.redact("key is " + KEY + " ok").contains(KEY));
        assertFalse(SecretRedactor.redact("abcd-efgh-ijkl-mnop").contains("abcd-efgh-ijkl-mnop"));
    }

    @Test
    void handlesNullAndEmpty() {
        assertNull(SecretRedactor.redact(null));
        assertEquals("", SecretRedactor.redact(""));
        assertEquals("", SecretRedactor.mask(null));
        assertEquals("", SecretRedactor.redactUrl(null));
    }

    @Test
    void maskNeverReturnsTheSecret() {
        String masked = SecretRedactor.mask(KEY);
        assertFalse(masked.contains(KEY));
        assertTrue(masked.chars().allMatch(c -> c == '•'));
    }

    @Test
    void credentialsToStringNeverLeaksKey() {
        StreamingCredentials creds = new StreamingCredentials("rtmp://live.twitch.tv/app", KEY);
        assertFalse(creds.toString().contains(KEY), "toString() must not leak the stream key");
        assertFalse(creds.redactedPublishUrl().contains(KEY));
    }

    @Test
    void looksSensitiveDetectsCredentials() {
        assertTrue(SecretRedactor.looksSensitive("my streamkey=abc"));
        assertTrue(SecretRedactor.looksSensitive(KEY));
        assertFalse(SecretRedactor.looksSensitive("just a normal log line"));
    }
}
