package dev.streamable.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TeeRecoveryTest {

    private static StreamDestination destination(String name) {
        return new StreamDestination(UUID.randomUUID(), name, StreamPlatform.TWITCH,
                new StreamingCredentials("rtmp://live.twitch.tv/app", "key-" + name));
    }

    @Test
    @DisplayName("split-off when one tee slave ERROR and a sibling is LIVE")
    void splitsWhenFailedBesideLiveSibling() {
        StreamDestination failed = destination("YouTube");
        StreamDestination live = destination("Twitch");
        failed.setState(DestinationState.ERROR);
        live.setState(DestinationState.LIVE);

        assertTrue(TeeRecovery.shouldSplitOff(failed, List.of(failed, live)));
    }

    @Test
    void doesNotSplitSoloGroup() {
        StreamDestination failed = destination("Twitch");
        failed.setState(DestinationState.ERROR);
        assertFalse(TeeRecovery.shouldSplitOff(failed, List.of(failed)));
    }

    @Test
    void doesNotSplitWhenNoHealthySibling() {
        StreamDestination a = destination("A");
        StreamDestination b = destination("B");
        a.setState(DestinationState.ERROR);
        b.setState(DestinationState.ERROR);
        assertFalse(TeeRecovery.shouldSplitOff(a, List.of(a, b)));
    }

    @Test
    void doesNotSplitLiveOrReconnectingTargets() {
        StreamDestination target = destination("Twitch");
        StreamDestination sibling = destination("YouTube");
        sibling.setState(DestinationState.LIVE);

        target.setState(DestinationState.LIVE);
        assertFalse(TeeRecovery.shouldSplitOff(target, List.of(target, sibling)));

        target.setState(DestinationState.RECONNECTING);
        assertFalse(TeeRecovery.shouldSplitOff(target, List.of(target, sibling)));
    }

    @Test
    void rejectsNullOrMissingTarget() {
        StreamDestination live = destination("Twitch");
        live.setState(DestinationState.LIVE);
        assertFalse(TeeRecovery.shouldSplitOff(null, List.of(live)));
        StreamDestination stranger = destination("Other");
        stranger.setState(DestinationState.ERROR);
        assertFalse(TeeRecovery.shouldSplitOff(stranger, List.of(live)));
    }
}
