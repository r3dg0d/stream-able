package dev.streamable.video;

import java.util.List;

/** The preset lists offered by the Video page, grouped the way people shop for monitors. */
public final class ResolutionPresets {

    public enum Category {
        STANDARD("Standard 16:9"),
        WIDESCREEN_16_10("16:10"),
        ULTRAWIDE("Ultrawide 21:9"),
        SUPER_ULTRAWIDE("Super Ultrawide 32:9");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public static final List<Resolution> STANDARD = List.of(
            new Resolution(1280, 720), new Resolution(1920, 1080),
            new Resolution(2560, 1440), new Resolution(3840, 2160));
    public static final List<Resolution> WIDESCREEN_16_10 = List.of(
            new Resolution(1920, 1200), new Resolution(2560, 1600));
    public static final List<Resolution> ULTRAWIDE = List.of(
            new Resolution(2560, 1080), new Resolution(3440, 1440), new Resolution(3840, 1600));
    public static final List<Resolution> SUPER_ULTRAWIDE = List.of(
            new Resolution(3840, 1080), new Resolution(5120, 1440));

    private ResolutionPresets() {
    }

    public static List<Resolution> of(Category category) {
        return switch (category) {
            case STANDARD -> STANDARD;
            case WIDESCREEN_16_10 -> WIDESCREEN_16_10;
            case ULTRAWIDE -> ULTRAWIDE;
            case SUPER_ULTRAWIDE -> SUPER_ULTRAWIDE;
        };
    }

    /** Every preset, in display order. */
    public static List<Resolution> all() {
        return java.util.stream.Stream.of(STANDARD, WIDESCREEN_16_10, ULTRAWIDE, SUPER_ULTRAWIDE)
                .flatMap(List::stream).toList();
    }

    /**
     * Tallest 16:9 this suggestion will offer. Taller presets exist, but most
     * live ingests cap around 1440p; {@link dev.streamable.video.OutputValidation}
     * still warns when a user picks something larger by hand.
     */
    static final int SUGGESTED_STREAM_MAX_HEIGHT = 1440;

    /**
     * A sensible streaming size for a program canvas: the largest common 16:9
     * preset that fits inside the canvas on both axes, and no taller than
     * {@link #SUGGESTED_STREAM_MAX_HEIGHT}. 3440×1440 and 5120×1440 suggest
     * 2560×1440; a 1080p canvas suggests 1920×1080; 4K suggests 2560×1440.
     *
     * <p>A canvas smaller than 720p on either axis used to fall through to
     * 1280×720, which upscales the picture. Those canvases now get the largest
     * even 16:9 that still fits, or the canvas's nearest even size when even
     * that would be below the minimum resolution.</p>
     */
    public static Resolution suggestedStreamOutput(Resolution canvas) {
        Resolution best = null;
        for (Resolution candidate : STANDARD) {
            if (candidate.height() > SUGGESTED_STREAM_MAX_HEIGHT) {
                continue;
            }
            if (candidate.width() <= canvas.width() && candidate.height() <= canvas.height()) {
                best = candidate;
            }
        }
        return best != null ? best : largestEvenSixteenByNine(canvas);
    }

    /**
     * How to map {@code canvas} onto {@code output} when the user asks for the
     * suggested stream size. A wider canvas (ultrawide, super ultrawide) is
     * center-cropped so the 16:9 frame is full. Same-shape and narrower
     * canvases use Fit so the whole picture stays visible.
     */
    public static ScalingMode suggestedStreamMode(Resolution canvas, Resolution output) {
        if (canvas.aspectRatio() > output.aspectRatio() + 0.02) {
            return ScalingMode.CENTER_CROP;
        }
        return ScalingMode.FIT;
    }

    /** Largest even 16:9 rectangle that fits in {@code canvas}, or nearest even canvas. */
    static Resolution largestEvenSixteenByNine(Resolution canvas) {
        int maxW = canvas.width();
        int maxH = Math.min(canvas.height(), SUGGESTED_STREAM_MAX_HEIGHT);
        int height = Math.min(maxH, maxW * 9 / 16);
        height -= height % 2;
        int width = height * 16 / 9;
        width -= width % 2;
        if (width > maxW) {
            width = maxW - (maxW % 2);
            height = width * 9 / 16;
            height -= height % 2;
        }
        if (width < Resolution.MIN_DIMENSION || height < Resolution.MIN_DIMENSION
                || width > canvas.width() || height > canvas.height()) {
            return canvas.nearestEven();
        }
        return new Resolution(width, height);
    }
}
