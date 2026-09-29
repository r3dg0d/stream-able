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

    private static StreamEncoderGroup groupOf(StreamDestination... destinations) {
        DestinationGrouping.Group group = new DestinationGrouping.Group(profile(), List.of(destinations));
        return new StreamEncoderGroup(group, "ffmpeg", 16, ReconnectPolicy.DEFAULT, 1920, 1080);
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
}
