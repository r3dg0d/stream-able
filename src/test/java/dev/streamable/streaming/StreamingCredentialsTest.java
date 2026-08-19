package dev.streamable.streaming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamingCredentialsTest {

    @Test
    void joinsUrlAndKeyWithSingleSlash() {
        assertEquals("rtmp://host/app/KEY",
                new StreamingCredentials("rtmp://host/app", "KEY").publishUrl());
        assertEquals("rtmp://host/app/KEY",
                new StreamingCredentials("rtmp://host/app/", "KEY").publishUrl());
        assertEquals("rtmp://host/app/KEY",
                new StreamingCredentials("rtmp://host/app", "/KEY").publishUrl());
        assertEquals("rtmp://host/app/KEY",
                new StreamingCredentials("rtmp://host/app/", "/KEY").publishUrl());
    }

    @Test
    void trimsWhitespaceFromPastedValues() {
        StreamingCredentials creds = new StreamingCredentials("  rtmp://host/app  ", "  KEY  ");
        assertEquals("rtmp://host/app/KEY", creds.publishUrl());
    }

    @Test
    void supportsKeyEmbeddedInUrl() {
        assertEquals("rtmp://host/app/embedded",
                new StreamingCredentials("rtmp://host/app/embedded", "").publishUrl());
    }

    @Test
    void rejectsMalformedInput() {
        assertNotNull(new StreamingCredentials("", "k").validate(true));
        assertNotNull(new StreamingCredentials("not-a-url", "k").validate(true));
        assertNotNull(new StreamingCredentials("rtmp://host/app", "").validate(true));
        assertNotNull(new StreamingCredentials("rtmp://ho st/app", "k").validate(true));
    }

    @Test
    void acceptsSupportedProtocols() {
        for (String scheme : new String[]{"rtmp", "rtmps", "srt", "http", "https"}) {
            assertNull(new StreamingCredentials(scheme + "://host/app", "k").validate(true),
                    scheme + " should be accepted");
        }
    }

    @Test
    void acceptsIngestUrlsWithNoApplicationPath() {
        // Kick publishes through Amazon IVS to rtmps://<host>/<key> with no
        // application path. An earlier version rejected this as malformed,
        // which broke a configuration that demonstrably works.
        assertNull(new StreamingCredentials(
                "rtmps://fa723fc1b171.global-contribute.live-video.net", "k").validate(true));
        assertEquals("rtmps://fa723fc1b171.global-contribute.live-video.net/k",
                new StreamingCredentials(
                        "rtmps://fa723fc1b171.global-contribute.live-video.net/", "k").publishUrl());
    }

    @Test
    void everyPlatformPresetProducesAUsablePublishUrl() {
        for (StreamPlatform platform : StreamPlatform.values()) {
            if (platform.defaultIngestUrl().isEmpty()) {
                continue;   // account specific; the user supplies it
            }
            StreamingCredentials credentials =
                    new StreamingCredentials(platform.defaultIngestUrl(), "testkey");
            assertNull(credentials.validate(platform.keyRequired()),
                    platform + " preset must validate");
            // host/app/key - three segments after the scheme.
            String afterScheme = credentials.publishUrl().split("//", 2)[1];
            assertTrue(afterScheme.chars().filter(c -> c == '/').count() >= 2,
                    platform + " publish URL needs an application path: " + credentials.publishUrl());
        }
    }

    @Test
    void keyIsOptionalWhenPlatformDoesNotRequireOne() {
        assertNull(new StreamingCredentials("rtmp://host/app", "").validate(false));
    }
}
