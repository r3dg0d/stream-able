package dev.streamable.source.transform;

/**
 * Position, size and rotation of a source on the program canvas.
 *
 * <p>The transform is a full affine placement, not an axis-aligned box: a
 * source may be moved, scaled non-uniformly and rotated, and mouse interaction
 * still has to land on the right pixel inside it. The chain is</p>
 *
 * <pre>
 *   source-local pixels  ->  translate by -pivot  ->  rotate  ->  translate by +centre  ->  canvas
 * </pre>
 *
 * <p>and {@link #canvasToLocal(Point2)} applies the exact inverse. The pivot is
 * the centre of the source, which is what makes the rotation knob behave the
 * way users expect from an image editor.</p>
 *
 * <p>All values are in <em>program-canvas pixels</em>. They are deliberately
 * the same numbers the user edits in the properties panel ({@code PosX},
 * {@code PosY}, {@code Width}, {@code Height}, {@code Rot}) so that dragging
 * and typing stay in sync.</p>
 *
 * @param x        left edge of the unrotated rectangle, in canvas pixels
 * @param y        top edge of the unrotated rectangle, in canvas pixels
 * @param width    width in canvas pixels, always {@code >= MIN_SIZE}
 * @param height   height in canvas pixels, always {@code >= MIN_SIZE}
 * @param rotation clockwise rotation in degrees, normalised to {@code [0, 360)}
 */
public record SourceTransform(double x, double y, double width, double height, double rotation) {

    /** Smallest permitted edge length; prevents zero-sized or inverted browsers. */
    public static final double MIN_SIZE = 16.0;
    /** Largest permitted edge length; prevents runaway dimensions from a bad drag. */
    public static final double MAX_SIZE = 16384.0;

    public SourceTransform {
        width = sanitiseSize(width);
        height = sanitiseSize(height);
        x = sanitiseCoord(x);
        y = sanitiseCoord(y);
        rotation = normaliseDegrees(rotation);
    }

    public static SourceTransform of(double x, double y, double width, double height) {
        return new SourceTransform(x, y, width, height, 0.0);
    }

    private static double sanitiseSize(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return MIN_SIZE;
        }
        return Math.clamp(value, MIN_SIZE, MAX_SIZE);
    }

    private static double sanitiseCoord(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.clamp(value, -MAX_SIZE, MAX_SIZE);
    }

    /** Normalises any angle - including NaN and huge values - into {@code [0, 360)}. */
    public static double normaliseDegrees(double degrees) {
        if (Double.isNaN(degrees) || Double.isInfinite(degrees)) {
            return 0.0;
        }
        double d = degrees % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    /** Centre of the source in canvas coordinates; also the rotation pivot. */
    public Point2 center() {
        return new Point2(x + width / 2.0, y + height / 2.0);
    }

    /** Maps a point in source-local pixels ({@code 0..width}, {@code 0..height}) to canvas space. */
    public Point2 localToCanvas(Point2 local) {
        Point2 relative = new Point2(local.x() - width / 2.0, local.y() - height / 2.0);
        return relative.rotate(rotation).plus(center());
    }

    /**
     * Maps a canvas point back into source-local pixels - the inverse of
     * {@link #localToCanvas(Point2)}. This is what makes clicking a button
     * inside a rotated, stretched browser source land on the right element.
     */
    public Point2 canvasToLocal(Point2 canvas) {
        Point2 relative = canvas.minus(center()).rotate(-rotation);
        return new Point2(relative.x() + width / 2.0, relative.y() + height / 2.0);
    }

    /** Maps a canvas point into normalised source coordinates in {@code [0,1]} (outside the box it escapes that range). */
    public Point2 canvasToNormalised(Point2 canvas) {
        Point2 local = canvasToLocal(canvas);
        return new Point2(local.x() / width, local.y() / height);
    }

    /** True when the canvas point falls inside the rotated rectangle. */
    public boolean contains(Point2 canvas) {
        Point2 local = canvasToLocal(canvas);
        return local.x() >= 0 && local.x() <= width && local.y() >= 0 && local.y() <= height;
    }

    /** The four corners in canvas space, clockwise from the top-left. */
    public Point2[] corners() {
        return new Point2[]{
                localToCanvas(new Point2(0, 0)),
                localToCanvas(new Point2(width, 0)),
                localToCanvas(new Point2(width, height)),
                localToCanvas(new Point2(0, height))
        };
    }

    /** Canvas position of a resize handle, or of the rotation knob. */
    public Point2 handlePosition(TransformHandle handle, double knobDistance) {
        if (handle.isRotation()) {
            // Sits above the top edge, rotating with the source so the knob is
            // always "up" relative to the box the user sees.
            return localToCanvas(new Point2(width / 2.0, -knobDistance));
        }
        return localToCanvas(new Point2(handle.u() * width, handle.v() * height));
    }

    public SourceTransform withPosition(double newX, double newY) {
        return new SourceTransform(newX, newY, width, height, rotation);
    }

    public SourceTransform withSize(double newWidth, double newHeight) {
        return new SourceTransform(x, y, newWidth, newHeight, rotation);
    }

    public SourceTransform withRotation(double newRotation) {
        return new SourceTransform(x, y, width, height, newRotation);
    }

    /** Moves by a canvas-space delta. */
    public SourceTransform translated(double dx, double dy) {
        return withPosition(x + dx, y + dy);
    }

    /** Recentres the box on the given canvas point, keeping size and rotation. */
    public SourceTransform centeredOn(Point2 canvasCenter) {
        return withPosition(canvasCenter.x() - width / 2.0, canvasCenter.y() - height / 2.0);
    }

    public double aspectRatio() {
        return width / height;
    }
}
