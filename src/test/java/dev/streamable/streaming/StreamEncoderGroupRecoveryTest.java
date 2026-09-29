package dev.streamable.streaming;

import dev.streamable.ffmpeg.AudioProfile;
import dev.streamable.ffmpeg.EncodeProfile;
import dev.streamable.ffmpeg.VideoEncoder;
import dev.streamable.ffmpeg.VideoProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Offline recovery of a failed tee slave onto a dedicated encoder. */
class StreamEncoderGroupRecoveryTest {

    private static EncodeProfile profile() {
        return new EncodeProfile(
                VideoProfile.liveDefault(VideoEncoder.X264, 1920, 1080, 60, 6000),
                AudioProfile.LIVE_DEFAULT);
    }

    private static StreamDestination destination(String name, String url) {
        return new StreamDestination(UUID.randomUUID(), name, StreamPlatform.CUSTOM,
                new StreamingCredentials(url, "key-" + name));
    }

    private static StreamEncoderGroup groupOf(ReconnectPolicy policy, StreamDestination... destinations) {
        DestinationGrouping.Group group = new DestinationGrouping.Group(profile(), List.of(destinations));
        return new StreamEncoderGroup(group, "ffmpeg", 16, policy, 1920, 1080);
    }

    private static StreamEncoderGroup groupOf(StreamDestination... destinations) {
        return groupOf(ReconnectPolicy.DEFAULT, destinations);
    }

    @Test
    @DisplayName("detach removes a destination without requiring a running process")
    void detachRemovesDestination() {
        StreamDestination twitch = destination("Twitch", "rtmp://twitch/app");
        StreamDestination youtube = destination("YouTube", "rtmps://youtube/live2");
        StreamEncoderGroup group = groupOf(twitch, youtube);

        assertTrue(group.detachDestination(youtube.id()));
        assertEquals(List.of(twitch), group.destinations());
        assertEquals(List.of(twitch.credentials().publishUrl()), group.group().publishUrls());
        assertFalse(group.detachDestination(youtube.id()));
    }

    @Test
    @DisplayName("split-off yields a solo group and leaves healthy siblings behind")
    void splitOffYieldsSoloRecoveryGroup() {
        StreamDestination twitch = destination("Twitch", "rtmp://twitch/app");
        StreamDestination youtube = destination("YouTube", "rtmps://youtube/live2");
        twitch.setState(DestinationState.LIVE);
        youtube.setState(DestinationState.ERROR);
        StreamEncoderGroup shared = groupOf(twitch, youtube);

        StreamEncoderGroup solo = shared.splitOffForRecovery(youtube.id());
        assertNotNull(solo);
        assertEquals(List.of(youtube), solo.destinations());
        assertEquals(List.of(twitch), shared.destinations());
        assertEquals(DestinationState.LIVE, twitch.state());
        assertEquals(DestinationState.ERROR, youtube.state());
        assertSame(shared.profile(), solo.profile());
    }

    @Test
    void splitOffRefusesWhenInappropriate() {
        StreamDestination twitch = destination("Twitch", "rtmp://twitch/app");
        StreamDestination youtube = destination("YouTube", "rtmps://youtube/live2");
        twitch.setState(DestinationState.LIVE);
        youtube.setState(DestinationState.LIVE);
        StreamEncoderGroup shared = groupOf(twitch, youtube);

        assertNull(shared.splitOffForRecovery(youtube.id()), "LIVE target must not split");
        assertEquals(2, shared.destinations().size());
    }

    @Test
    @DisplayName("mid-stream tee error schedules dedicated-encoder recovery with backoff")
    void errorLineSchedulesAutoRecovery() {
        StreamDestination twitch = destination("Twitch", "rtmp://twitch/app");
        StreamDestination youtube = destination("YouTube", "rtmps://youtube/live2");
        twitch.setState(DestinationState.LIVE);
        youtube.setState(DestinationState.LIVE);
        ReconnectPolicy policy = new ReconnectPolicy(true, 5_000L, 60_000L, 2.0, 10);
        StreamEncoderGroup shared = groupOf(policy, twitch, youtube);

        assertEquals(1, shared.noteTeeSlaveFailures(
                "Error opening output url rtmps://youtube/live2: Input/output error"));
        assertEquals(DestinationState.ERROR, youtube.state());

        long now = 1_000_000L;
        shared.scheduleTeeRecoveries(now);

        assertEquals(DestinationState.RECONNECTING, youtube.state());
        assertEquals(DestinationState.LIVE, twitch.state());
        assertTrue(youtube.lastError().contains("dedicated encoder"));
        assertEquals(1, youtube.reconnectAttempts());
        assertTrue(shared.pollReadyTeeRecoveries(now).isEmpty(), "not due yet");
        assertTrue(shared.pollReadyTeeRecoveries(now + 4_999L).isEmpty());
        assertEquals(List.of(youtube.id()), shared.pollReadyTeeRecoveries(now + 5_000L));
        assertTrue(shared.pollReadyTeeRecoveries(now + 5_000L).isEmpty(), "schedule consumed");
    }

    @Test
    @DisplayName("disabled reconnect policy leaves tee slave in ERROR (manual only)")
    void disabledPolicyDoesNotAutoRecover() {
        StreamDestination twitch = destination("Twitch", "rtmp://twitch/app");
        StreamDestination youtube = destination("YouTube", "rtmps://youtube/live2");
        twitch.setState(DestinationState.LIVE);
        youtube.setState(DestinationState.LIVE);
        ReconnectPolicy off = new ReconnectPolicy(false, 5_000L, 60_000L, 2.0, 10);
        StreamEncoderGroup shared = groupOf(off, twitch, youtube);

        shared.onErrorLine("Error opening output url rtmps://youtube/live2: I/O error");
        assertEquals(DestinationState.ERROR, youtube.state());
        assertTrue(shared.pollReadyTeeRecoveries(System.currentTimeMillis() + 60_000L).isEmpty());
        assertTrue(TeeRecovery.shouldSplitOff(youtube, shared.destinations()),
                "manual Reconnect can still split");
    }

    @Test
    @DisplayName("solo failure (no LIVE sibling) is not auto-scheduled")
    void noScheduleWithoutLiveSibling() {
        StreamDestination a = destination("A", "rtmp://a/app");
        StreamDestination b = destination("B", "rtmp://b/app");
        a.setState(DestinationState.ERROR);
        b.setState(DestinationState.ERROR);
        StreamEncoderGroup shared = groupOf(a, b);

        assertEquals(1, shared.noteTeeSlaveFailures("Error opening output url rtmp://a/app"));
        shared.scheduleTeeRecoveries(1_000L);
        assertEquals(DestinationState.ERROR, a.state());
        assertTrue(shared.pollReadyTeeRecoveries(1_000L + 60_000L).isEmpty());
    }

    @Test
    @DisplayName("split-off works for RECONNECTING scheduled targets")
    void splitOffWhileReconnecting() {
        StreamDestination twitch = destination("Twitch", "rtmp://twitch/app");
        StreamDestination youtube = destination("YouTube", "rtmps://youtube/live2");
        twitch.setState(DestinationState.LIVE);
        youtube.setState(DestinationState.RECONNECTING);
        StreamEncoderGroup shared = groupOf(twitch, youtube);

        StreamEncoderGroup solo = shared.splitOffForRecovery(youtube.id());
        assertNotNull(solo);
        assertEquals(List.of(youtube), solo.destinations());
        assertEquals(List.of(twitch), shared.destinations());
    }

    @Test
    @DisplayName("exhausted tee recovery attempts leave a clear ERROR message")
    void exhaustedAttemptsLeaveClearError() {
        StreamDestination twitch = destination("Twitch", "rtmp://twitch/app");
        StreamDestination youtube = destination("YouTube", "rtmps://youtube/live2");
        twitch.setState(DestinationState.LIVE);
        youtube.setState(DestinationState.LIVE);
        ReconnectPolicy policy = new ReconnectPolicy(true, 1_000L, 1_000L, 2.0, 2);
        StreamEncoderGroup shared = groupOf(policy, twitch, youtube);

        long now = 10_000L;
        shared.noteTeeSlaveFailures("Error opening output url rtmps://youtube/live2: I/O error");
        shared.scheduleTeeRecoveries(now);
        assertEquals(DestinationState.RECONNECTING, youtube.state());
        assertEquals(List.of(youtube.id()), shared.pollReadyTeeRecoveries(now + 1_000L));

        // Simulate another mid-stream fail after the first recovery attempt was consumed.
        youtube.setState(DestinationState.ERROR);
        shared.noteTeeSlaveFailures("Error opening output url rtmps://youtube/live2: I/O error");
        shared.scheduleTeeRecoveries(now + 2_000L);
        assertEquals(DestinationState.RECONNECTING, youtube.state());
        assertEquals(List.of(youtube.id()), shared.pollReadyTeeRecoveries(now + 3_000L));

        youtube.setState(DestinationState.ERROR);
        shared.noteTeeSlaveFailures("Error opening output url rtmps://youtube/live2: I/O error");
        shared.scheduleTeeRecoveries(now + 4_000L);
        assertEquals(DestinationState.ERROR, youtube.state());
        assertTrue(youtube.lastError().toLowerCase().contains("exhausted"));
        assertTrue(shared.pollReadyTeeRecoveries(now + 60_000L).isEmpty());
    }

    @Test
    @DisplayName("disabling a scheduled recovery cancels it and does not split later")
    void disableCancelsPendingRecovery() {
        StreamDestination twitch = destination("Twitch", "rtmp://twitch/app");
        StreamDestination youtube = destination("YouTube", "rtmps://youtube/live2");
        twitch.setState(DestinationState.LIVE);
        youtube.setState(DestinationState.LIVE);
        StreamEncoderGroup shared = groupOf(twitch, youtube);

        long now = 50_000L;
        shared.noteTeeSlaveFailures("Error opening output url rtmps://youtube/live2: I/O error");
        shared.scheduleTeeRecoveries(now);
        assertEquals(DestinationState.RECONNECTING, youtube.state());

        assertTrue(shared.cancelTeeRecovery(youtube.id()));
        youtube.setEnabled(false);
        assertEquals(DestinationState.DISABLED, youtube.state());
        assertTrue(shared.pollReadyTeeRecoveries(now + 60_000L).isEmpty());
        assertNull(shared.splitOffForRecovery(youtube.id()));
        assertEquals(2, shared.destinations().size());
    }

    @Test
    @DisplayName("poll drops ineligible schedules and reverts RECONNECTING when no LIVE sibling")
    void pollCancelsWhenNoLiveSibling() {
        StreamDestination a = destination("A", "rtmp://a/app");
        StreamDestination b = destination("B", "rtmp://b/app");
        a.setState(DestinationState.LIVE);
        b.setState(DestinationState.LIVE);
        StreamEncoderGroup shared = groupOf(a, b);

        long now = 80_000L;
        shared.noteTeeSlaveFailures("Error opening output url rtmp://b/app: I/O error");
        shared.scheduleTeeRecoveries(now);
        assertEquals(DestinationState.RECONNECTING, b.state());

        // Sibling also dies before the backoff elapses.
        a.setState(DestinationState.ERROR);
        assertTrue(shared.pollReadyTeeRecoveries(now + 60_000L).isEmpty());
        assertEquals(DestinationState.ERROR, b.state());
        assertTrue(b.lastError().toLowerCase().contains("cancelled")
                || b.lastError().toLowerCase().contains("no healthy sibling"));
    }
}
