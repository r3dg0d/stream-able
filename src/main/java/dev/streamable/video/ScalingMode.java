package dev.streamable.video;

/**
 * How a source picture is mapped onto an output of a different size.
 *
 * <p>The geometry for every mode is computed in exactly one place,
 * {@link OutputTransform#compute}. Stretching is never chosen implicitly: every
 * default in Stream-able is {@link #FIT} or {@link #NATIVE}.</p>
 */
public enum ScalingMode {
    /**
     * Output equals the source size, pixel for pixel. If the sizes differ the
     * source is placed 1:1 at the centre (cropped or padded, never resampled).
     */
    NATIVE("Native", "Pixel-for-pixel at the source resolution."),
    /** Uniform scale until everything is visible; bars fill the remainder. */
    FIT("Fit", "Shows the whole picture. Adds black bars when the shapes differ."),
    /** Uniform scale until the output is covered; the overflow is cropped evenly. */
    FILL("Fill", "Fills the whole output with no bars. Crops the edges that do not fit."),
    /**
     * The central output-sized region at native sharpness (no resampling).
     * When the source is smaller than the output on an axis it behaves like
     * {@link #FILL}, so there are never bars.
     */
    CENTER_CROP("Center Crop", "Takes the central region at full sharpness, without scaling."),
    /** Independent X/Y scale. Distorts; only ever used when explicitly chosen. */
    STRETCH("Stretch", "Distorts the picture to fill the output. Only use it deliberately.");

    private final String displayName;
    private final String description;

    ScalingMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public static ScalingMode parse(String value, ScalingMode fallback) {
        if (value == null) {
            return fallback;
        }
        for (ScalingMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return fallback;
    }
}
