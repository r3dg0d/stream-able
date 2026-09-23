package dev.streamable.video;

/**
 * Coarse aspect-ratio family, used for labels, presets and gentle warnings.
 *
 * <p>Classification is tolerance based: monitors are marketed as "21:9" while
 * 3440x1440 is really 43:18 (2.389) and 2560x1080 is 64:27 (2.370). Comparing
 * ratios with exact equality would call every real ultrawide "custom".</p>
 */
public enum AspectClass {
    /** 4:3, 5:4 and similar. */
    STANDARD("Standard"),
    /** 16:9 and 16:10. */
    WIDESCREEN("Widescreen"),
    /** 21:9-class monitors (2560x1080, 3440x1440, 3840x1600). */
    ULTRAWIDE("Ultrawide"),
    /** 32:9-class monitors (3840x1080, 5120x1440). */
    SUPER_ULTRAWIDE("Super Ultrawide"),
    /** Portrait (taller than wide), e.g. 1080x1920 for vertical video. */
    PORTRAIT("Portrait"),
    /** Anything that does not sit near a common family. */
    CUSTOM("Custom");

    private final String displayName;

    AspectClass(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Classifies a width/height ratio. */
    public static AspectClass of(double ratio) {
        if (!Double.isFinite(ratio) || ratio <= 0) {
            return CUSTOM;
        }
        if (ratio < 0.95) {
            return PORTRAIT;
        }
        if (ratio < 1.45) {
            return STANDARD;
        }
        if (ratio <= 1.85) {
            return WIDESCREEN;
        }
        if (ratio >= 2.2 && ratio <= 2.6) {
            return ULTRAWIDE;
        }
        if (ratio >= 3.3 && ratio <= 3.8) {
            return SUPER_ULTRAWIDE;
        }
        return CUSTOM;
    }
}
