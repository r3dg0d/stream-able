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
     * A sensible streaming size for a program canvas: the largest common 16:9
     * output that does not upscale it. 3440x1440 suggests 2560x1440, 5120x1440
     * suggests 2560x1440, a 1080p canvas suggests 1920x1080.
     */
    public static Resolution suggestedStreamOutput(Resolution canvas) {
        Resolution best = STANDARD.getFirst();
        int limit = Math.min(canvas.height(), 1440);
        for (Resolution candidate : STANDARD) {
            if (candidate.height() <= limit) {
                best = candidate;
            }
        }
        return best;
    }
}
