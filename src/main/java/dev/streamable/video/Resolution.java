package dev.streamable.video;

import java.util.Locale;

/**
 * A pixel resolution - the one canonical representation used for the game
 * framebuffer, the program canvas, the recording output, the streaming output
 * and the preview.
 *
 * <p>Construction only rejects impossible values (non-positive or beyond
 * {@link #MAX_DIMENSION}); whether a size is <em>suitable</em> for a given
 * encoder is a separate question answered by {@link OutputValidation}, so
 * nothing here ever silently changes what the user typed.</p>
 */
public record Resolution(int width, int height) {

    /** Hard ceiling per axis; beyond this no GPU in scope can allocate a texture. */
    public static final int MAX_DIMENSION = 16384;
    /** Hard ceiling on total pixels (8192 x 8192), bounding every buffer allocation. */
    public static final long MAX_PIXELS = 8192L * 8192L;
    public static final int MIN_DIMENSION = 16;

    public static final Resolution HD = new Resolution(1280, 720);
    public static final Resolution FULL_HD = new Resolution(1920, 1080);

    /** Marketing ratios matched with tolerance, in preference order. */
    private static final int[][] NAMED_RATIOS = {
            {16, 9}, {16, 10}, {4, 3}, {5, 4}, {21, 9}, {32, 9}, {32, 10}, {9, 16}, {1, 1}
    };
    private static final double NAMED_RATIO_TOLERANCE = 0.035;

    public Resolution {
        if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
            throw new IllegalArgumentException("Resolution must be at least " + MIN_DIMENSION + " pixels: "
                    + width + "x" + height);
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("Resolution exceeds " + MAX_DIMENSION + " pixels per side: "
                    + width + "x" + height);
        }
        if ((long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("Resolution exceeds " + MAX_PIXELS + " total pixels: "
                    + width + "x" + height);
        }
    }

    /** Parses {@code 3440x1440} or {@code 3440 × 1440}. */
    public static Resolution parse(String text) {
        String[] parts = text.trim().toLowerCase(Locale.ROOT).split("\\s*[x×*]\\s*");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected WIDTHxHEIGHT: " + text);
        }
        return new Resolution(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
    }

    /** Validating factory that returns {@code null} instead of throwing. */
    public static Resolution tryOf(int width, int height) {
        try {
            return new Resolution(width, height);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Clamps arbitrary (e.g. window) sizes into the valid range. Never used for user input. */
    public static Resolution clamped(int width, int height) {
        int w = Math.clamp(width, MIN_DIMENSION, MAX_DIMENSION);
        int h = Math.clamp(height, MIN_DIMENSION, MAX_DIMENSION);
        while ((long) w * h > MAX_PIXELS) {
            w = Math.max(MIN_DIMENSION, w * 7 / 8);
            h = Math.max(MIN_DIMENSION, h * 7 / 8);
        }
        return new Resolution(w, h);
    }

    public double aspectRatio() {
        return width / (double) height;
    }

    public long pixelCount() {
        return (long) width * height;
    }

    public boolean isEven() {
        return width % 2 == 0 && height % 2 == 0;
    }

    public AspectClass aspectClass() {
        return AspectClass.of(aspectRatio());
    }

    public boolean isUltrawide() {
        return aspectClass() == AspectClass.ULTRAWIDE;
    }

    public boolean isSuperUltrawide() {
        return aspectClass() == AspectClass.SUPER_ULTRAWIDE;
    }

    public boolean isWiderThan(Resolution other) {
        return aspectRatio() > other.aspectRatio() + 1e-9;
    }

    /** Byte size of a frame at {@code bytesPerPixel}, with overflow checking. */
    public long frameBytes(int bytesPerPixel) {
        return Math.multiplyExact(pixelCount(), (long) bytesPerPixel);
    }

    /** Frame size that fits an {@code int} buffer, or throws. */
    public int frameBytesInt(int bytesPerPixel) {
        return Math.toIntExact(frameBytes(bytesPerPixel));
    }

    /** Exact reduced ratio, e.g. {@code 43:18} for 3440x1440. */
    public String reducedRatio() {
        int divisor = gcd(width, height);
        return (width / divisor) + ":" + (height / divisor);
    }

    /**
     * The ratio people call this resolution, e.g. {@code 21:9} for 3440x1440,
     * or the exact reduced ratio when it is not near a common one.
     */
    public String marketedRatio() {
        double ratio = aspectRatio();
        for (int[] named : NAMED_RATIOS) {
            double target = named[0] / (double) named[1];
            if (Math.abs(ratio - target) / target <= NAMED_RATIO_TOLERANCE) {
                return named[0] + ":" + named[1];
            }
        }
        return reducedRatio();
    }

    /** {@code 3440 × 1440}. */
    public String label() {
        return width + " × " + height;
    }

    /** {@code 3440x1440}, the form FFmpeg and config files use. */
    public String toFfmpegSize() {
        return width + "x" + height;
    }

    /** Scaled copy, rounded to even dimensions (for derived, not user-entered, sizes). */
    public Resolution scaledEven(double factor) {
        int w = Math.max(MIN_DIMENSION, (int) Math.round(width * factor / 2.0) * 2);
        int h = Math.max(MIN_DIMENSION, (int) Math.round(height * factor / 2.0) * 2);
        return clamped(w, h);
    }

    /** Nearest even resolution, for suggestions shown to the user. */
    public Resolution nearestEven() {
        return new Resolution(width - (width % 2), height - (height % 2));
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.max(1, a);
    }

    @Override
    public String toString() {
        return toFfmpegSize();
    }
}
