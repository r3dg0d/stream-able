package dev.streamable.streaming;

import java.util.List;
import java.util.UUID;

/**
 * Decides when a mid-stream tee slave can be recovered without restarting
 * healthy siblings.
 *
 * <p>FFmpeg's {@code tee} muxer with {@code onfail=ignore} leaves a dead slave
 * in the shared process; there is no API to revive it there. Recovering means
 * detaching that destination and starting a dedicated encoder for it alone,
 * which costs a second encode but keeps every still-{@link DestinationState#LIVE}
 * sibling on the original process.</p>
 *
 * <p>Recovery is scheduled automatically from {@link StreamEncoderGroup} using
 * the session {@link ReconnectPolicy} backoff; Destinations → Reconnect still
 * splits immediately when the user asks.</p>
 */
public final class TeeRecovery {

    private TeeRecovery() {
    }

    /**
     * True when {@code target} failed (or is waiting to recover) inside a shared
     * tee while at least one sibling is still publishing.
     */
    public static boolean shouldSplitOff(StreamDestination target, List<StreamDestination> siblings) {
        if (target == null || siblings == null || siblings.size() < 2) {
            return false;
        }
        DestinationState state = target.state();
        if (state != DestinationState.ERROR && state != DestinationState.RECONNECTING) {
            return false;
        }
        UUID id = target.id();
        boolean found = false;
        boolean healthySibling = false;
        for (StreamDestination sibling : siblings) {
            if (sibling.id().equals(id)) {
                found = true;
                continue;
            }
            if (sibling.state() == DestinationState.LIVE) {
                healthySibling = true;
            }
        }
        return found && healthySibling;
    }
}
