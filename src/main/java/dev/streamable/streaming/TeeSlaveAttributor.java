package dev.streamable.streaming;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps an FFmpeg {@code tee} / ingest error line onto the destinations it names.
 *
 * <p>With {@code onfail=ignore}, a dead slave keeps the shared encoder alive.
 * FFmpeg mentions the failing URL on stderr but has no per-slave status API, so
 * Stream-able attributes those lines here and flags only the matching
 * destinations as {@link DestinationState#ERROR} while the others stay live.</p>
 *
 * <p>Matching is deliberately conservative: the (already redacted) error text
 * must contain the destination's ingest URL or its redacted publish URL. When
 * one ingest URL is a proper prefix of another and both match, only the longer
 * (more specific) destination is kept.</p>
 */
public final class TeeSlaveAttributor {

    private TeeSlaveAttributor() {
    }

    /**
     * Destinations named in {@code errorLine}.
     *
     * @param errorLine already-redacted FFmpeg stderr (safe to store / show)
     */
    public static List<StreamDestination> matching(String errorLine, List<StreamDestination> destinations) {
        if (errorLine == null || errorLine.isBlank() || destinations == null || destinations.isEmpty()) {
            return List.of();
        }
        String lower = errorLine.toLowerCase(Locale.ROOT);
        List<StreamDestination> matches = new ArrayList<>();
        for (StreamDestination destination : destinations) {
            if (matchesDestination(lower, destination)) {
                matches.add(destination);
            }
        }
        if (matches.size() <= 1) {
            return List.copyOf(matches);
        }
        List<StreamDestination> snapshot = List.copyOf(matches);
        matches.removeIf(candidate -> {
            String ingest = candidate.credentials().ingestUrl().toLowerCase(Locale.ROOT);
            if (ingest.isEmpty()) {
                return false;
            }
            for (StreamDestination other : snapshot) {
                if (other == candidate) {
                    continue;
                }
                String otherIngest = other.credentials().ingestUrl().toLowerCase(Locale.ROOT);
                if (otherIngest.startsWith(ingest) && otherIngest.length() > ingest.length()) {
                    return true;
                }
            }
            return false;
        });
        return List.copyOf(matches);
    }

    /**
     * Marks matching destinations {@link DestinationState#ERROR} with the error
     * detail; leaves every other destination untouched.
     *
     * @return how many destinations were flagged
     */
    public static int flagFailedSlaves(String errorLine, List<StreamDestination> destinations) {
        List<StreamDestination> matches = matching(errorLine, destinations);
        for (StreamDestination destination : matches) {
            destination.setState(DestinationState.ERROR);
            destination.setLastError(errorLine);
        }
        return matches.size();
    }

    static boolean matchesDestination(String lowerError, StreamDestination destination) {
        StreamingCredentials credentials = destination.credentials();
        String ingest = credentials.ingestUrl().toLowerCase(Locale.ROOT);
        if (!ingest.isEmpty() && lowerError.contains(ingest)) {
            return true;
        }
        String redactedPublish = credentials.redactedPublishUrl().toLowerCase(Locale.ROOT);
        return !redactedPublish.isEmpty() && lowerError.contains(redactedPublish);
    }
}
