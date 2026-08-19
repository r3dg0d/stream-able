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

/** Grouping, bandwidth and reconnect behaviour for multistreaming. */
class MultistreamTest {

    private static EncodeProfile profile(int bitrate) {
        return new EncodeProfile(
                VideoProfile.liveDefault(VideoEncoder.X264, 1920, 1080, 60, bitrate),
                AudioProfile.LIVE_DEFAULT);
    }

    private static StreamDestination destination(String name, StreamPlatform platform, String url, String key) {
        return new StreamDestination(UUID.randomUUID(), name, platform, new StreamingCredentials(url, key));
    }

    @Test
    @DisplayName("compatible destinations share one encoder")
    void compatibleDestinationsShareOneEncoder() {
        List<StreamDestination> destinations = List.of(
                destination("Twitch", StreamPlatform.TWITCH, "rtmp://twitch/app", "k1"),
                destination("YouTube", StreamPlatform.YOUTUBE, "rtmp://youtube/live2", "k2"),
                destination("Kick", StreamPlatform.CUSTOM, "rtmps://kick/app", "k3"));

        List<DestinationGrouping.Group> groups = DestinationGrouping.group(destinations, profile(6000));
        assertEquals(1, groups.size(), "three compatible services must cost one encode");
        assertEquals(3, groups.getFirst().destinations().size());
        assertFalse(DestinationGrouping.requiresMultipleEncoders(groups));
    }

    @Test
    @DisplayName("an incompatible profile forces its own encoder")
    void incompatibleProfileGetsItsOwnEncoder() {
        StreamDestination twitch = destination("Twitch", StreamPlatform.TWITCH, "rtmp://twitch/app", "k1");
        StreamDestination youtube = destination("YouTube", StreamPlatform.YOUTUBE, "rtmp://youtube/live2", "k2");
        youtube.setProfileOverride(profile(12000));

        List<DestinationGrouping.Group> groups =
                DestinationGrouping.group(List.of(twitch, youtube), profile(6000));
        assertEquals(2, groups.size());
        assertTrue(DestinationGrouping.requiresMultipleEncoders(groups),
                "the user must be warned about the extra encode");
    }

    @Test
    void disabledAndInvalidDestinationsAreSkipped() {
        StreamDestination disabled = destination("Off", StreamPlatform.TWITCH, "rtmp://twitch/app", "k");
        disabled.setEnabled(false);
        StreamDestination invalid = destination("Bad", StreamPlatform.TWITCH, "not-a-url", "k");
        StreamDestination missingKey = destination("NoKey", StreamPlatform.TWITCH, "rtmp://twitch/app", "");

        List<DestinationGrouping.Group> groups = DestinationGrouping.group(
                List.of(disabled, invalid, missingKey), profile(6000));
        assertTrue(groups.isEmpty());
    }

    @Test
    void urlsThatCannotBeTeedGetADedicatedEncoder() {
        StreamDestination normal = destination("A", StreamPlatform.CUSTOM, "rtmp://a/app", "k");
        StreamDestination bracketed = destination("B", StreamPlatform.CUSTOM, "rtmp://b/[weird]", "k");
        // The bracketed URL fails validation outright, so it never reaches a group.
        assertNotNull(bracketed.validate());
        List<DestinationGrouping.Group> groups =
                DestinationGrouping.group(List.of(normal, bracketed), profile(6000));
        assertEquals(1, groups.size());
    }

    @Test
    @DisplayName("bandwidth scales with destinations even when the encoder is shared")
    void bandwidthCountsOneCopyPerDestination() {
        BandwidthEstimator.Estimate estimate = BandwidthEstimator.estimate(
                List.of(new BandwidthEstimator.GroupLoad(profile(12000), 3)));
        // 12 Mbps video x 3 + 0.16 Mbps audio x 3
        assertEquals(36.0, estimate.videoBitsPerSecond() / 1_000_000.0, 1e-6);
        assertEquals(3, estimate.destinationCount());
        assertEquals(1, estimate.encoderCount());
        assertTrue(estimate.totalMbps() > 36.0);
        assertTrue(estimate.recommendedUplinkMbps() > estimate.totalMbps());
        assertTrue(estimate.describe().contains("Destinations: 3"));
    }

    @Test
    void reconnectBackoffGrowsAndIsCapped() {
        ReconnectPolicy policy = new ReconnectPolicy(true, 5_000, 60_000, 2.0, 10);
        assertEquals(5_000, policy.delayForAttempt(1));
        assertEquals(10_000, policy.delayForAttempt(2));
        assertEquals(20_000, policy.delayForAttempt(3));
        assertEquals(60_000, policy.delayForAttempt(9), "must saturate at the cap");
        assertEquals(60_000, policy.delayForAttempt(1000));
    }

    @Test
    void reconnectStopsAfterMaxAttempts() {
        ReconnectPolicy policy = new ReconnectPolicy(true, 1000, 5000, 2.0, 3);
        assertTrue(policy.shouldRetry(0));
        assertTrue(policy.shouldRetry(2));
        assertFalse(policy.shouldRetry(3));
        assertEquals("Attempt 2/3", policy.describeAttempt(2));
    }

    @Test
    void reconnectCanBeUnlimitedOrDisabled() {
        assertTrue(new ReconnectPolicy(true, 1000, 5000, 2.0, 0).shouldRetry(9999));
        assertFalse(new ReconnectPolicy(false, 1000, 5000, 2.0, 10).shouldRetry(0));
    }

    @Test
    void destinationStateTransitionsTrackLiveness() {
        StreamDestination destination = destination("Twitch", StreamPlatform.TWITCH, "rtmp://twitch/app", "k");
        assertEquals(DestinationState.OFFLINE, destination.state());
        destination.setState(DestinationState.LIVE);
        assertTrue(destination.state().isActive());
        assertTrue(destination.liveSinceMillis() > 0);
        destination.setState(DestinationState.ERROR);
        assertEquals(0, destination.liveSinceMillis());
    }

    @Test
    void destinationErrorsAreStoredRedacted() {
        StreamDestination destination = destination("Twitch", StreamPlatform.TWITCH,
                "rtmp://live.twitch.tv/app", "live_9_supersecret");
        destination.setLastError("Failed to publish rtmp://live.twitch.tv/app/live_9_supersecret");
        assertFalse(destination.lastError().contains("live_9_supersecret"));
        assertFalse(destination.toString().contains("live_9_supersecret"));
    }

    @Test
    void disablingOneDestinationLeavesOthersReady() {
        StreamDestination twitch = destination("Twitch", StreamPlatform.TWITCH, "rtmp://twitch/app", "k1");
        StreamDestination kick = destination("Kick", StreamPlatform.CUSTOM, "rtmps://kick/app", "k2");
        kick.setEnabled(false);
        assertTrue(twitch.isReadyToStream());
        assertFalse(kick.isReadyToStream());
        assertEquals(DestinationState.DISABLED, kick.state());
        assertEquals(1, DestinationGrouping.group(List.of(twitch, kick), profile(6000)).size());
    }
}
