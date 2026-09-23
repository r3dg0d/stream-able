package dev.streamable.video;

/**
 * The canonical source-to-output geometry.
 *
 * <p>Every consumer - the GPU output scaler, the preview's safe-area guides,
 * mouse mapping through the preview, the Stream Health description - derives
 * its numbers from this one computation, so what the viewer receives and what
 * the streamer is shown can never disagree.</p>
 *
 * <p>Coordinates are pixels with a top-left origin. {@code src*} describe the
 * rectangle of the <em>source</em> that is sampled; {@code dst*} describe
 * where it lands in the <em>output</em>. Output pixels outside the destination
 * rectangle are bars (black).</p>
 *
 * @param source source resolution
 * @param output output resolution
 * @param mode   scaling mode that produced this geometry
 * @param srcX   left of the sampled source region
 * @param srcY   top of the sampled source region
 * @param srcW   width of the sampled source region
 * @param srcH   height of the sampled source region
 * @param dstX   left of the drawn region in the output
 * @param dstY   top of the drawn region in the output
 * @param dstW   width of the drawn region in the output
 * @param dstH   height of the drawn region in the output
 */
public record OutputTransform(Resolution source, Resolution output, ScalingMode mode,
                              double srcX, double srcY, double srcW, double srcH,
                              double dstX, double dstY, double dstW, double dstH) {

    private static final double EPSILON = 1e-6;

    /** Computes the geometry for a mode. The only place scaling maths lives. */
    public static OutputTransform compute(Resolution source, Resolution output, ScalingMode mode) {
        double sw = source.width();
        double sh = source.height();
        double ow = output.width();
        double oh = output.height();
        return switch (mode) {
            case STRETCH -> new OutputTransform(source, output, mode, 0, 0, sw, sh, 0, 0, ow, oh);
            case FIT -> {
                double scale = Math.min(ow / sw, oh / sh);
                double w = sw * scale;
                double h = sh * scale;
                yield new OutputTransform(source, output, mode, 0, 0, sw, sh, (ow - w) / 2.0, (oh - h) / 2.0, w, h);
            }
            case FILL -> fill(source, output, mode);
            case CENTER_CROP -> {
                if (sw >= ow && sh >= oh) {
                    yield new OutputTransform(source, output, mode,
                            (sw - ow) / 2.0, (sh - oh) / 2.0, ow, oh, 0, 0, ow, oh);
                }
                yield fill(source, output, mode);
            }
            case NATIVE -> {
                // 1:1 pixels, centred. Each axis independently either crops the
                // source (source larger) or pads the output (source smaller).
                double w = Math.min(sw, ow);
                double h = Math.min(sh, oh);
                yield new OutputTransform(source, output, mode,
                        (sw - w) / 2.0, (sh - h) / 2.0, w, h,
                        (ow - w) / 2.0, (oh - h) / 2.0, w, h);
            }
        };
    }

    private static OutputTransform fill(Resolution source, Resolution output, ScalingMode mode) {
        double sw = source.width();
        double sh = source.height();
        double ow = output.width();
        double oh = output.height();
        double scale = Math.max(ow / sw, oh / sh);
        double visibleW = ow / scale;
        double visibleH = oh / scale;
        return new OutputTransform(source, output, mode,
                (sw - visibleW) / 2.0, (sh - visibleH) / 2.0, visibleW, visibleH, 0, 0, ow, oh);
    }

    public double scaleX() {
        return dstW / srcW;
    }

    public double scaleY() {
        return dstH / srcH;
    }

    /** True when source and output are the same size and nothing moves. */
    public boolean isIdentity() {
        return source.equals(output) && Math.abs(srcX) < EPSILON && Math.abs(srcY) < EPSILON
                && Math.abs(dstX) < EPSILON && Math.abs(dstY) < EPSILON
                && Math.abs(srcW - source.width()) < EPSILON && Math.abs(srcH - source.height()) < EPSILON;
    }

    /** Whether the aspect ratio of the picture is altered. */
    public boolean distorts() {
        return Math.abs(scaleX() - scaleY()) > 1e-4 * Math.max(scaleX(), scaleY());
    }

    /** True when part of the source is not shown. */
    public boolean cropsSource() {
        return srcX > EPSILON || srcY > EPSILON
                || srcW < source.width() - EPSILON || srcH < source.height() - EPSILON;
    }

    /** True when the output has bars around the picture. */
    public boolean hasBars() {
        return dstX > EPSILON || dstY > EPSILON
                || dstW < output.width() - EPSILON || dstH < output.height() - EPSILON;
    }

    /** Bars on the left and right. */
    public boolean isPillarboxed() {
        return dstX > EPSILON;
    }

    /** Bars on the top and bottom. */
    public boolean isLetterboxed() {
        return dstY > EPSILON;
    }

    /** Whether the output is resampled (anything but 1:1 pixel copies). */
    public boolean resamples() {
        return Math.abs(scaleX() - 1.0) > 1e-9 || Math.abs(scaleY() - 1.0) > 1e-9;
    }

    /** Maps a source pixel position to the output. */
    public double[] sourceToOutput(double x, double y) {
        return new double[]{dstX + (x - srcX) * scaleX(), dstY + (y - srcY) * scaleY()};
    }

    /** Maps an output pixel position back into the source (may fall outside it). */
    public double[] outputToSource(double x, double y) {
        return new double[]{srcX + (x - dstX) / scaleX(), srcY + (y - dstY) / scaleY()};
    }

    /** Normalised texture coordinates of the sampled region: u0, v0, u1, v1 (top-left origin). */
    public float[] sourceUv() {
        return new float[]{
                (float) (srcX / source.width()), (float) (srcY / source.height()),
                (float) ((srcX + srcW) / source.width()), (float) ((srcY + srcH) / source.height())
        };
    }

    /** Plain-language summary, e.g. "Center Crop - shows the central 1920 × 1080 of 3440 × 1440". */
    public String describe() {
        String visible = Math.round(srcW) + " × " + Math.round(srcH);
        return switch (mode) {
            case NATIVE -> source.equals(output)
                    ? "Native - pixel for pixel"
                    : "Native - 1:1 pixels, " + (cropsSource() ? "cropped to " + visible : "") + (hasBars() ? " with borders" : "");
            case FIT -> hasBars()
                    ? "Fit - whole picture, " + (isPillarboxed() ? "bars left and right" : "bars top and bottom")
                    : "Fit - whole picture, no bars";
            case FILL -> cropsSource()
                    ? "Fill - shows the central " + visible + " of " + source.label() + ", scaled"
                    : "Fill - whole picture";
            case CENTER_CROP -> cropsSource()
                    ? "Center Crop - shows the central " + visible + " of " + source.label()
                    + (resamples() ? ", scaled" : " at full sharpness")
                    : "Center Crop - whole picture";
            case STRETCH -> distorts() ? "Stretch - picture is distorted" : "Stretch - same shape, no distortion";
        };
    }
}
