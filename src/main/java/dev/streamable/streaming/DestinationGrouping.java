package dev.streamable.streaming;

import dev.streamable.ffmpeg.EncodeProfile;
import dev.streamable.ffmpeg.FFmpegCommandBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides how many encoders a multistream session needs.
 *
 * <p>Destinations that share an encode profile are served by a single FFmpeg
 * process and fanned out with the {@code tee} muxer, so enabling Twitch,
 * YouTube and Kick on the same settings costs <em>one</em> encode rather than
 * three. A destination with an overridden profile - or a URL that cannot take
 * part in a tee fan-out - is given its own encoder, and the caller warns the
 * user about the additional GPU/CPU cost.</p>
 */
public final class DestinationGrouping {

    private DestinationGrouping() {
    }

    /**
     * Groups ready-to-stream destinations by their effective encode profile.
     *
     * @param destinations  all configured destinations; disabled or invalid ones are skipped
     * @param globalProfile the session-wide profile used when a destination has no override
     * @return one group per distinct encoder, in stable configuration order
     */
    public static List<Group> group(List<StreamDestination> destinations, EncodeProfile globalProfile) {
        Map<Object, List<StreamDestination>> byKey = new LinkedHashMap<>();
        List<Group> exclusive = new ArrayList<>();

        for (StreamDestination destination : destinations) {
            if (!destination.isReadyToStream()) {
                continue;
            }
            EncodeProfile profile = destination.effectiveProfile(globalProfile);
            if (!FFmpegCommandBuilder.isTeeSafe(destination.credentials().publishUrl())) {
                // Cannot be fanned out safely - give it a dedicated encoder.
                exclusive.add(new Group(profile, List.of(destination)));
                continue;
            }
            byKey.computeIfAbsent(profile, k -> new ArrayList<>()).add(destination);
        }

        List<Group> groups = new ArrayList<>(byKey.size() + exclusive.size());
        for (Map.Entry<Object, List<StreamDestination>> entry : byKey.entrySet()) {
            groups.add(new Group((EncodeProfile) entry.getKey(), List.copyOf(entry.getValue())));
        }
        groups.addAll(exclusive);
        return List.copyOf(groups);
    }

    /**
     * True when the configuration forces more than one simultaneous encode,
     * which is worth warning about before going live.
     */
    public static boolean requiresMultipleEncoders(List<Group> groups) {
        return groups.size() > 1;
    }

    /** One encoder and the destinations it feeds. */
    public record Group(EncodeProfile profile, List<StreamDestination> destinations) {

        public List<String> publishUrls() {
            return destinations.stream().map(d -> d.credentials().publishUrl()).toList();
        }

        /** Redacted URLs, safe to log. */
        public List<String> redactedPublishUrls() {
            return destinations.stream().map(d -> d.credentials().redactedPublishUrl()).toList();
        }
    }
}
