package dev.streamable.source.transform;

/**
 * The nine interactive controls of the OBS-style transform box.
 *
 * <p>Each handle is described by its normalised position inside the source
 * rectangle, where {@code 0} is the left/top edge, {@code 0.5} the centre and
 * {@code 1} the right/bottom edge. The <em>anchor</em> of a resize is simply
 * the mirrored handle, which is what keeps the opposite edge pinned while
 * dragging.</p>
 */
public enum TransformHandle {

    TOP_LEFT(0.0, 0.0),
    TOP_CENTER(0.5, 0.0),
    TOP_RIGHT(1.0, 0.0),
    MIDDLE_LEFT(0.0, 0.5),
    MIDDLE_RIGHT(1.0, 0.5),
    BOTTOM_LEFT(0.0, 1.0),
    BOTTOM_CENTER(0.5, 1.0),
    BOTTOM_RIGHT(1.0, 1.0),
    /** The circular knob drawn above the source; drags change rotation only. */
    ROTATION(0.5, 0.0);

    private final double u;
    private final double v;

    TransformHandle(double u, double v) {
        this.u = u;
        this.v = v;
    }

    public double u() {
        return u;
    }

    public double v() {
        return v;
    }

    public boolean isRotation() {
        return this == ROTATION;
    }

    /** True when dragging this handle changes the width. */
    public boolean affectsWidth() {
        return !isRotation() && u != 0.5;
    }

    /** True when dragging this handle changes the height. */
    public boolean affectsHeight() {
        return !isRotation() && v != 0.5;
    }

    /** True for the four corner handles, which can resize both axes at once. */
    public boolean isCorner() {
        return affectsWidth() && affectsHeight();
    }

    /**
     * The handle diagonally/axially opposite this one. Resizing keeps the
     * anchor's canvas position fixed, so the source grows away from it.
     */
    public TransformHandle opposite() {
        return switch (this) {
            case TOP_LEFT -> BOTTOM_RIGHT;
            case TOP_CENTER -> BOTTOM_CENTER;
            case TOP_RIGHT -> BOTTOM_LEFT;
            case MIDDLE_LEFT -> MIDDLE_RIGHT;
            case MIDDLE_RIGHT -> MIDDLE_LEFT;
            case BOTTOM_LEFT -> TOP_RIGHT;
            case BOTTOM_CENTER -> TOP_CENTER;
            case BOTTOM_RIGHT -> TOP_LEFT;
            case ROTATION -> ROTATION;
        };
    }

    /** The eight resize handles, excluding {@link #ROTATION}. */
    public static TransformHandle[] resizeHandles() {
        return new TransformHandle[]{
                TOP_LEFT, TOP_CENTER, TOP_RIGHT,
                MIDDLE_LEFT, MIDDLE_RIGHT,
                BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
        };
    }
}
