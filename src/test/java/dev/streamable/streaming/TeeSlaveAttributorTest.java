package dev.streamable.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TeeSlaveAttributorTest {

    private static StreamDestination destination(String name, StreamPlatform platform, String url, String key) {
        return new StreamDestination(UUID.randomUUID(), name, platform, new StreamingCredentials(url, key));
    }

    @Test
    @DisplayName("attributes an error line to the destination whose ingest URL it names")
    void attributesByIngestUrl() {
        StreamDestination twitch = destination("Twitch", StreamPlatform.TWITCH,
                "rtmp://live.twitch.tv/app", "live_1_key");
        StreamDestination youtube = destination("YouTube", StreamPlatform.YOUTUBE,
                "rtmps://a.rtmps.youtube.com/live2", "xxxx-yyyy-zzzz-wwww");
        twitch.setState(DestinationState.LIVE);
        youtube.setState(DestinationState.LIVE);

        String line = "Error opening output rtmp://live.twitch.tv/app/<REDACTED>: Input/output error";
        int flagged = TeeSlaveAttributor.flagFailedSlaves(line, List.of(twitch, youtube));

        assertEquals(1, flagged);
        assertEquals(DestinationState.ERROR, twitch.state());
        assertTrue(twitch.lastError().contains("live.twitch.tv"));
        assertFalse(twitch.lastError().contains("live_1_key"));
        assertEquals(DestinationState.LIVE, youtube.state(), "healthy tee sibling must stay live");
        assertEquals("", youtube.lastError());
    }

    @Test
    @DisplayName("matches a redacted publish URL when the ingest path alone is absent")
    void attributesByRedactedPublishUrl() {
        StreamDestination custom = destination("Custom", StreamPlatform.CUSTOM,
                "rtmps://ingest.example/live", "secretkey99");
        custom.setState(DestinationState.LIVE);
        String redacted = custom.credentials().redactedPublishUrl();
        assertTrue(redacted.contains("<REDACTED>"));

        int flagged = TeeSlaveAttributor.flagFailedSlaves(
                "tee slave failed: " + redacted, List.of(custom));
        assertEquals(1, flagged);
        assertEquals(DestinationState.ERROR, custom.state());
    }

    @Test
    void ignoresUnrelatedErrorLines() {
        StreamDestination twitch = destination("Twitch", StreamPlatform.TWITCH,
                "rtmp://live.twitch.tv/app", "k");
        twitch.setState(DestinationState.LIVE);
        assertEquals(0, TeeSlaveAttributor.flagFailedSlaves(
                "Error opening input filters", List.of(twitch)));
        assertEquals(DestinationState.LIVE, twitch.state());
    }

    @Test
    void prefersMoreSpecificIngestWhenOneIsAPrefix() {
        StreamDestination root = destination("Root", StreamPlatform.CUSTOM,
                "rtmp://host.example/app", "k1");
        StreamDestination nested = destination("Nested", StreamPlatform.CUSTOM,
                "rtmp://host.example/app/extra", "k2");
        root.setState(DestinationState.LIVE);
        nested.setState(DestinationState.LIVE);

        List<StreamDestination> matches = TeeSlaveAttributor.matching(
                "failed rtmp://host.example/app/extra/<REDACTED>", List.of(root, nested));
        assertEquals(1, matches.size());
        assertSame(nested, matches.getFirst());
    }

    @Test
    void blankOrNullInputMatchesNothing() {
        StreamDestination twitch = destination("Twitch", StreamPlatform.TWITCH,
                "rtmp://live.twitch.tv/app", "k");
        assertTrue(TeeSlaveAttributor.matching(null, List.of(twitch)).isEmpty());
        assertTrue(TeeSlaveAttributor.matching("  ", List.of(twitch)).isEmpty());
        assertTrue(TeeSlaveAttributor.matching("error", List.of()).isEmpty());
    }
}
