package dev.streamable.streaming;

import dev.streamable.ffmpeg.EncodeProfile;
import dev.streamable.ffmpeg.VideoProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Caps all destination copies, including overridden profiles, to 80% of measured upload. */
public final class UploadBudget {
    private UploadBudget() { }

    public record Plan(List<DestinationGrouping.Group> groups, long requestedKbps,
                       long effectiveKbps, long limitKbps, boolean reduced) {
        public String describe() {
            if (limitKbps == 0) return "Upload speed unknown: set your measured upload to budget every destination.";
            return String.format(Locale.ROOT, "Upload budget: %.2f Mbps of requested %.2f Mbps; %.2f Mbps limit (20%% reserved).%s",
                    effectiveKbps / 1000.0, requestedKbps / 1000.0, limitKbps / 1000.0,
                    reduced ? " Video bitrate will be reduced when starting." : " Requested quality fits.");
        }
    }

    public static Plan plan(List<DestinationGrouping.Group> groups, double uploadMbps, boolean withAudio) {
        long video = 0, audio = 0, count = 0;
        for (var group : groups) {
            if (Double.isFinite(uploadMbps) && uploadMbps > 0 && !group.profile().video().rateControl().isLiveSafe()) {
                throw new IllegalArgumentException("Upload budgeting requires CBR or VBR for every destination.");
            }
            int copies = group.destinations().size();
            count += copies;
            video += peak(group.profile().video()) * (long) copies;
            if (withAudio) audio += group.profile().audio().bitrateKbps() * (long) copies;
        }
        long requested = video + audio;
        if (!Double.isFinite(uploadMbps) || uploadMbps <= 0) {
            return new Plan(List.copyOf(groups), requested, requested, 0, false);
        }
        long limit = (long) Math.floor(Math.min(uploadMbps, 10000) * 800 + 1e-8);
        if (requested <= limit) return new Plan(List.copyOf(groups), requested, requested, limit, false);
        long minimum = count * 100;
        if (limit - audio < minimum) {
            throw new IllegalArgumentException("Upload budget is too small for the enabled destinations and audio. "
                    + "Disable destinations or increase the measured upload speed.");
        }
        double scale = (limit - audio - minimum) / (double) (video - minimum);
        List<DestinationGrouping.Group> adjusted = new ArrayList<>();
        long effective = audio;
        for (var group : groups) {
            VideoProfile v = group.profile().video();
            int ceiling = 100 + (int) Math.floor((peak(v) - 100) * scale + 1e-8);
            int target = Math.max(100, Math.min(ceiling, (int) Math.floor(v.bitrateKbps() * ceiling / (double) peak(v))));
            VideoProfile capped = new VideoProfile(v.encoder(), v.width(), v.height(), v.fps(), v.rateControl(),
                    target, ceiling, Math.min(v.bufferSizeKbits(), ceiling * 2), v.keyframeSeconds(),
                    v.preset(), v.h264Profile(), v.bFrames(), v.quality());
            adjusted.add(new DestinationGrouping.Group(new EncodeProfile(capped, group.profile().audio()), group.destinations()));
            effective += peak(capped) * (long) group.destinations().size();
        }
        return new Plan(List.copyOf(adjusted), requested, effective, limit, true);
    }

    private static int peak(VideoProfile v) {
        return Math.max(v.bitrateKbps(), v.maxBitrateKbps());
    }
}
