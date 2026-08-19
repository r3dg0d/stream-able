package dev.streamable.source.transform;

import java.util.List;

/**
 * Pure geometry for the source editor: resizing, rotating and hit-testing.
 *
 * <p>Everything here is static and side-effect free so it can be unit tested
 * without a Minecraft client. The invariant that matters most is that a resize
 * keeps the <em>anchor</em> - the handle opposite the one being dragged -
 * pinned to the same canvas pixel, even when the source is rotated. Without
 * that, dragging the corner of a rotated box makes it visibly swim away from
 * the cursor.</p>
 */
public final class TransformMath {

    /** Default rotation snap increment, applied while the snap modifier is held. */
    public static final double ROTATION_SNAP_DEGREES = 15.0;

    private TransformMath() {
    }

    /**
     * Resizes a transform by dragging one of the eight resize handles.
     *
     * @param original       transform before the drag
     * @param handle         the handle being dragged (must not be {@link TransformHandle#ROTATION})
     * @param mouseCanvas    current mouse position in canvas coordinates
     * @param freeTransform  {@code true} for non-uniform (Shift) resize, {@code false} to
     *                       preserve the original aspect ratio
     * @return the resized transform, with the anchor handle held fixed in canvas space
     */
    public static SourceTransform resize(SourceTransform original, TransformHandle handle,
                                         Point2 mouseCanvas, boolean freeTransform) {
        if (handle.isRotation()) {
            return original;
        }
        TransformHandle anchor = handle.opposite();
        double w = original.width();
        double h = original.height();

        Point2 anchorLocal = new Point2(anchor.u() * w, anchor.v() * h);
        Point2 anchorCanvas = original.localToCanvas(anchorLocal);
        Point2 mouseLocal = original.canvasToLocal(mouseCanvas);

        // Signed extent from the anchor towards the dragged handle. Using the
        // signed value (rather than an absolute one) means dragging past the
        // anchor clamps at MIN_SIZE instead of flipping the source inside out.
        double newWidth = w;
        double newHeight = h;
        if (handle.affectsWidth()) {
            newWidth = handle.u() > 0.5 ? mouseLocal.x() - anchorLocal.x() : anchorLocal.x() - mouseLocal.x();
        }
        if (handle.affectsHeight()) {
            newHeight = handle.v() > 0.5 ? mouseLocal.y() - anchorLocal.y() : anchorLocal.y() - mouseLocal.y();
        }

        if (!freeTransform) {
            // Proportional: derive a single scale factor and apply it to both
            // axes. For a corner we follow whichever axis the user moved more,
            // which keeps the box tracking the cursor naturally.
            double scaleW = handle.affectsWidth() ? newWidth / w : 1.0;
            double scaleH = handle.affectsHeight() ? newHeight / h : 1.0;
            double scale;
            if (handle.isCorner()) {
                scale = Math.abs(scaleW - 1.0) >= Math.abs(scaleH - 1.0) ? scaleW : scaleH;
            } else {
                scale = handle.affectsWidth() ? scaleW : scaleH;
            }
            if (Double.isNaN(scale) || Double.isInfinite(scale)) {
                scale = 1.0;
            }
            newWidth = w * scale;
            newHeight = h * scale;
        }

        newWidth = Math.clamp(newWidth, SourceTransform.MIN_SIZE, SourceTransform.MAX_SIZE);
        newHeight = Math.clamp(newHeight, SourceTransform.MIN_SIZE, SourceTransform.MAX_SIZE);

        // Re-derive the centre so the anchor lands on exactly the same canvas
        // pixel it occupied before the drag.
        Point2 anchorLocalNew = new Point2(anchor.u() * newWidth, anchor.v() * newHeight);
        Point2 relative = new Point2(anchorLocalNew.x() - newWidth / 2.0, anchorLocalNew.y() - newHeight / 2.0);
        Point2 newCenter = anchorCanvas.minus(relative.rotate(original.rotation()));

        return new SourceTransform(
                newCenter.x() - newWidth / 2.0,
                newCenter.y() - newHeight / 2.0,
                newWidth,
                newHeight,
                original.rotation());
    }

    /**
     * Rotates a transform so its rotation knob follows the cursor.
     *
     * <p>The knob sits directly above the centre at zero rotation, so the
     * source angle is the cursor's bearing from the centre plus a quarter turn.</p>
     *
     * @param snap when {@code true}, snaps to {@link #ROTATION_SNAP_DEGREES} increments
     */
    public static SourceTransform rotate(SourceTransform original, Point2 mouseCanvas, boolean snap) {
        Point2 center = original.center();
        double dx = mouseCanvas.x() - center.x();
        double dy = mouseCanvas.y() - center.y();
        if (dx == 0 && dy == 0) {
            return original;
        }
        double degrees = Math.toDegrees(Math.atan2(dy, dx)) + 90.0;
        if (snap) {
            degrees = Math.round(degrees / ROTATION_SNAP_DEGREES) * ROTATION_SNAP_DEGREES;
        }
        return original.withRotation(degrees);
    }

    /**
     * Finds which control the cursor is over, if any.
     *
     * <p>The rotation knob wins ties because it sits outside the box and is the
     * smaller target.</p>
     *
     * @param handleRadius picking radius around each handle, in canvas pixels
     * @param knobDistance distance of the rotation knob above the top edge
     * @return the handle under the cursor, or {@code null}
     */
    public static TransformHandle pickHandle(SourceTransform transform, Point2 mouseCanvas,
                                             double handleRadius, double knobDistance) {
        Point2 knob = transform.handlePosition(TransformHandle.ROTATION, knobDistance);
        if (knob.distanceTo(mouseCanvas) <= handleRadius) {
            return TransformHandle.ROTATION;
        }
        TransformHandle best = null;
        double bestDistance = handleRadius;
        for (TransformHandle handle : TransformHandle.resizeHandles()) {
            double distance = transform.handlePosition(handle, knobDistance).distanceTo(mouseCanvas);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = handle;
            }
        }
        return best;
    }

    /**
     * Applies edge/centre snapping to a dragged position.
     *
     * <p>Snapping is advisory: it nudges the box when an edge or centre line is
     * within {@code threshold} canvas pixels of a guide, and is a no-op when
     * {@code threshold <= 0} so the user can always turn it off.</p>
     *
     * @param candidate    the un-snapped transform produced by the drag
     * @param canvasWidth  program canvas width
     * @param canvasHeight program canvas height
     * @param others       transforms of the other sources to snap against
     * @param threshold    snap distance in canvas pixels; {@code <= 0} disables snapping
     */
    public static SourceTransform snap(SourceTransform candidate, int canvasWidth, int canvasHeight,
                                       List<SourceTransform> others, double threshold) {
        if (threshold <= 0) {
            return candidate;
        }
        // Snapping compares axis-aligned extents, which is only meaningful for
        // an unrotated box; a rotated source keeps its free position.
        if (candidate.rotation() != 0.0) {
            return candidate;
        }

        double x = candidate.x();
        double y = candidate.y();
        double w = candidate.width();
        double h = candidate.height();

        double bestDx = Double.MAX_VALUE;
        double bestDy = Double.MAX_VALUE;

        double[] guideX = buildGuides(canvasWidth, others, true);
        double[] guideY = buildGuides(canvasHeight, others, false);

        for (double guide : guideX) {
            bestDx = closer(bestDx, guide - x);            // left edge
            bestDx = closer(bestDx, guide - (x + w));      // right edge
            bestDx = closer(bestDx, guide - (x + w / 2));  // centre
        }
        for (double guide : guideY) {
            bestDy = closer(bestDy, guide - y);
            bestDy = closer(bestDy, guide - (y + h));
            bestDy = closer(bestDy, guide - (y + h / 2));
        }

        double dx = Math.abs(bestDx) <= threshold ? bestDx : 0;
        double dy = Math.abs(bestDy) <= threshold ? bestDy : 0;
        return candidate.translated(dx, dy);
    }

    private static double[] buildGuides(int canvasExtent, List<SourceTransform> others, boolean horizontal) {
        int extra = others == null ? 0 : others.size() * 3;
        double[] guides = new double[3 + extra];
        guides[0] = 0;
        guides[1] = canvasExtent / 2.0;
        guides[2] = canvasExtent;
        int i = 3;
        if (others != null) {
            for (SourceTransform other : others) {
                double start = horizontal ? other.x() : other.y();
                double size = horizontal ? other.width() : other.height();
                guides[i++] = start;
                guides[i++] = start + size / 2.0;
                guides[i++] = start + size;
            }
        }
        return guides;
    }

    private static double closer(double current, double candidate) {
        return Math.abs(candidate) < Math.abs(current) ? candidate : current;
    }
}
