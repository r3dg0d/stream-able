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
        // Pathless custom servers remain supported; known Kick hosts are
        // normalized separately because their FFmpeg endpoint needs /app.
        assertNull(new StreamingCredentials("rtmps://custom.example.org", "k").validate(true));
        assertEquals("rtmps://custom.example.org/k",
                new StreamingCredentials("rtmps://custom.example.org/", "k").publishUrl());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "rtmps://fa723fc1b171.global-contribute.live-video.net",
            "rtmps://fa723fc1b171.global-contribute.live-video.net/",
            "rtmps://fa723fc1b171.global-contribute.live-video.net/app",
            "rtmps://fa723fc1b171.global-contribute.live-video.net:443/app/",
            "rtmp://fa723fc1b171.global-contribute.live-video.net:1935"
    })
    void kickTargetsUseTlsAndTheRequiredApplicationPath(String input) {
        StreamingCredentials credentials = new StreamingCredentials(input, "fixture_key");
        assertEquals("rtmps://fa723fc1b171.global-contribute.live-video.net:443/app/fixture_key",
                credentials.publishUrl());
        assertFalse(credentials.redactedPublishUrl().contains("fixture_key"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "rtmp://live.twitch.tv/app", "rtmps://a.rtmps.youtube.com/live2",
            "rtmps://va.pscp.tv:443/x", "rtmp://localhost/custom",
            "rtmps://live-video.net.example.org/app",
            "rtmps://example.org/live-video.net/app",
            "rtmps://abc.global-contribute.live-video.net/custom",
            "rtmps://abc.global-contribute.live-video.net/app?token=fixture"
    })
    void otherServicesAndExplicitCustomPathsAreUnchanged(String input) {
        assertEquals(input, new StreamingCredentials(input, "fixture_key").ingestUrl());
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
