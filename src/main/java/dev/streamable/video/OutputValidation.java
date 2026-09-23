package dev.streamable.video;

import dev.streamable.ffmpeg.VideoEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks an output configuration and explains problems in plain language.
 *
 * <p>Nothing here modifies the user's values. Hard errors stop an output from
 * starting and say exactly what to change; warnings are shown but never block,
 * because a knowledgeable user may have good reasons (an ultrawide stream to a
 * self-hosted server, for instance).</p>
 */
public final class OutputValidation {

    public enum Severity { ERROR, WARNING, INFO }

    public record Issue(Severity severity, String message) {
    }

    /** Where the output goes; streaming gets platform-compatibility advice. */
    public enum Target { RECORDING, STREAMING }

    private OutputValidation() {
    }

    public static List<Issue> validate(Resolution output, int fps, VideoEncoder encoder, Target target) {
        List<Issue> issues = new ArrayList<>();
        if (!output.isEven()) {
            Resolution even = output.nearestEven();
            issues.add(new Issue(Severity.ERROR, String.format(Locale.ROOT,
                    "%s has an odd dimension. The encoders use 4:2:0 colour, which requires even "
                            + "width and height. Use %s instead.", output.label(), even.label())));
        }
        int maxWidth = maxWidth(encoder);
        if (encoder != null && (output.width() > maxWidth || output.height() > maxWidth)) {
            issues.add(new Issue(Severity.ERROR, String.format(Locale.ROOT,
                    "%s supports at most %d pixels per side, so %s cannot be encoded with it. "
                            + "Choose HEVC/AV1 hardware encoding or software x264, or a smaller output.",
                    encoder.displayName(), maxWidth, output.label())));
        }
        if (output.width() < 128 || output.height() < 72) {
            issues.add(new Issue(Severity.ERROR, output.label() + " is too small to encode reliably."));
        }
        double pixelRate = output.pixelCount() * (double) fps;
        if (pixelRate > 3840.0 * 2160.0 * 60.0 * 1.01) {
            issues.add(new Issue(Severity.WARNING, String.format(Locale.ROOT,
                    "%s at %d FPS is more pixels per second than 4K60. Expect heavy GPU readback and "
                            + "encoder load; hardware encoding is strongly recommended.", output.label(), fps)));
        }
        if (target == Target.STREAMING) {
            AspectClass aspect = output.aspectClass();
            if (aspect == AspectClass.ULTRAWIDE || aspect == AspectClass.SUPER_ULTRAWIDE) {
                issues.add(new Issue(Severity.WARNING, String.format(Locale.ROOT,
                        "%s is a %s output. Some services or players may letterbox, pillarbox or transcode it "
                                + "differently than a 16:9 stream.", output.label(),
                        aspect.displayName().toLowerCase(Locale.ROOT))));
            } else if (!output.marketedRatio().equals("16:9") && aspect != AspectClass.PORTRAIT) {
                issues.add(new Issue(Severity.INFO, output.label() + " is not 16:9; most services display "
                        + "16:9 natively and add bars to other shapes."));
            }
            if (output.height() > 1440 || pixelRate > 2560.0 * 1440.0 * 60.0 * 1.01) {
                issues.add(new Issue(Severity.INFO, "Many services cap live ingest resolution and bitrate for "
                        + "most accounts; check your service's limits for " + output.label() + "."));
            }
        }
        return List.copyOf(issues);
    }

    public static boolean hasErrors(List<Issue> issues) {
        return issues.stream().anyMatch(issue -> issue.severity() == Severity.ERROR);
    }

    /** First error message, or {@code null}. */
    public static String firstError(List<Issue> issues) {
        return issues.stream().filter(issue -> issue.severity() == Severity.ERROR)
                .map(Issue::message).findFirst().orElse(null);
    }

    /**
     * Largest supported side per encoder. H.264 hardware encoders from all three
     * vendors are limited to 4096; HEVC/AV1 hardware and the software encoders
     * go to 8192 or beyond.
     */
    public static int maxWidth(VideoEncoder encoder) {
        if (encoder == null) {
            return Resolution.MAX_DIMENSION;
        }
        if (encoder.isHardware() && encoder.codec() == VideoEncoder.Codec.H264) {
            return 4096;
        }
        if (encoder.isHardware()) {
            return 8192;
        }
        return switch (encoder) {
            case X264, X265 -> 16384;
            default -> 8192;
        };
    }
}
