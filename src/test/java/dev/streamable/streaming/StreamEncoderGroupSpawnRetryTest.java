package dev.streamable.streaming;

import dev.streamable.ffmpeg.AudioProfile;
import dev.streamable.ffmpeg.EncodeProfile;
import dev.streamable.ffmpeg.VideoEncoder;
import dev.streamable.ffmpeg.VideoProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A spawn that throws never fires the FFmpeg exit callback. Reconnect has to
 * arm from that failure or the destination stays down for the rest of the session.
 */
class StreamEncoderGroupSpawnRetryTest {

    private static StreamEncoderGroup group(ReconnectPolicy policy) {
        StreamDestination destination = new StreamDestination(UUID.randomUUID(), "Local",
                StreamPlatform.CUSTOM, new StreamingCredentials("rtmp://127.0.0.1/live", "fixture-key"));
        EncodeProfile profile = new EncodeProfile(
                VideoProfile.liveDefault(VideoEncoder.X264, 1280, 720, 30, 2500),
                AudioProfile.LIVE_DEFAULT);
        DestinationGrouping.Group grouped = new DestinationGrouping.Group(profile, List.of(destination));
        return new StreamEncoderGroup(grouped, "/no/such/stream-able-ffmpeg", 16, policy, 1280, 720);
    }

    @Test
    @DisplayName("a missing encoder arms reconnect instead of sticking on error")
    void spawnFailureSchedulesReconnect() {
        ReconnectPolicy policy = new ReconnectPolicy(true, 5_000L, 60_000L, 2.0, 10);
        StreamEncoderGroup encoder = group(policy);
        long before = System.currentTimeMillis();

        String error = encoder.start(false);

        long after = System.currentTimeMillis();
        StreamDestination destination = encoder.destinations().getFirst();
        assertNotNull(error);
        assertTrue(error.startsWith("Could not start the encoder:"), error);
        assertEquals(DestinationState.RECONNECTING, destination.state());
        assertTrue(destination.lastError().contains("Retrying in 5 seconds"), destination.lastError());
        assertEquals("Attempt 1/10", destination.lastError().substring(destination.lastError().lastIndexOf("Attempt")));
        long due = encoder.scheduledRetryAtMillis();
        assertTrue(due >= before + 5_000L && due <= after + 5_000L, "due at " + due);
        assertFalse(encoder.isReadyToRetry(due - 1));
        assertTrue(encoder.isReadyToRetry(due));
        assertFalse(encoder.isRunning());
    }

    @Test
    @DisplayName("disabled reconnect leaves a spawn failure on error")
    void disabledPolicyDoesNotSchedule() {
        StreamEncoderGroup encoder = group(new ReconnectPolicy(false, 5_000L, 60_000L, 2.0, 10));

        String error = encoder.start(false);

        assertNotNull(error);
        assertEquals(DestinationState.ERROR, encoder.destinations().getFirst().state());
        assertEquals(0L, encoder.scheduledRetryAtMillis());
        assertFalse(encoder.isReadyToRetry(System.currentTimeMillis() + 60_000L));
    }

    @Test
    @DisplayName("repeated spawn failures stop after the startup attempt limit")
    void startupSpawnFailuresGiveUp() {
        StreamEncoderGroup encoder = group(ReconnectPolicy.DEFAULT);

        assertNotNull(encoder.start(false));
        assertEquals(DestinationState.RECONNECTING, encoder.destinations().getFirst().state());
        assertNotNull(encoder.retry(false));
        assertEquals(DestinationState.RECONNECTING, encoder.destinations().getFirst().state());
        assertTrue(encoder.destinations().getFirst().lastError().contains("Attempt 2/"));

        assertNotNull(encoder.retry(false));
        assertEquals(DestinationState.ERROR, encoder.destinations().getFirst().state());
        assertEquals(0L, encoder.scheduledRetryAtMillis());
        assertTrue(encoder.destinations().getFirst().lastError().startsWith("Could not start the broadcast."),
                encoder.destinations().getFirst().lastError());
        assertEquals(3, encoder.attempts());
    }
}
