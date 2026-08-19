package dev.streamable.compositor;

import dev.streamable.source.BrowserSource;
import dev.streamable.source.transform.Point2;
import dev.streamable.source.transform.SourceEditor;
import dev.streamable.source.transform.SourceTransform;
import dev.streamable.source.transform.TransformHandle;

/**
 * Draws the OBS-style transform box around the selected source.
 *
 * <pre>
 *               O   &lt;- rotation knob
 *               |
 *      #--------#--------#
 *      |                 |
 *      |  Browser Source |
 *      |                 |
 *      #--------#--------#
 * </pre>
 *
 * <p>Everything is built from the source's rotated corners rather than an
 * axis-aligned box, so the outline, the eight handles and the knob all stay
 * glued to the source at any angle. The overlay is drawn only to the local
 * screen - never into the program frame - so viewers never see edit handles.</p>
 */
public final class EditorOverlayRenderer {

    /** OBS-like red. */
    private static final int BORDER_COLOUR = 0xFFFF2D2D;
    private static final int HANDLE_COLOUR = 0xFFFF2D2D;
    private static final int HANDLE_EDGE_COLOUR = 0xFFFFFFFF;
    private static final int LOCKED_COLOUR = 0xFF9E9E9E;

    private static final double BORDER_THICKNESS = 2.0;
    private static final double HANDLE_SIZE = 8.0;
    private static final double KNOB_SIZE = 10.0;

    private EditorOverlayRenderer() {
    }

    /**
     * Draws the selection outline and controls.
     *
     * @param mapping canvas-to-screen mapping, so handles keep a constant
     *                on-screen size regardless of canvas resolution
     */
    public static void render(GlQuadRenderer renderer, SourceEditor editor,
                              ProgramCanvas.Mapping mapping, int screenWidth, int screenHeight) {
        BrowserSource source = editor.selected();
        if (source == null || !renderer.isUsable()) {
            return;
        }
        SourceTransform transform = source.transform();
        int colour = source.locked() ? LOCKED_COLOUR : BORDER_COLOUR;

        Point2[] canvasCorners = transform.corners();
        Point2[] corners = new Point2[4];
        for (int i = 0; i < 4; i++) {
            corners[i] = mapping.toTarget(canvasCorners[i]);
        }

        // Outline: one quad per edge, expanded outwards by half the thickness.
        for (int i = 0; i < 4; i++) {
            drawLine(renderer, corners[i], corners[(i + 1) % 4], BORDER_THICKNESS,
                    screenWidth, screenHeight, colour);
        }
        if (source.locked()) {
            return;   // locked sources show the outline but expose no controls
        }

        // The rotation knob and its stalk.
        Point2 topCentre = midpoint(corners[0], corners[1]);
        Point2 knob = mapping.toTarget(
                transform.handlePosition(TransformHandle.ROTATION, SourceEditor.ROTATION_KNOB_DISTANCE));
        drawLine(renderer, topCentre, knob, BORDER_THICKNESS, screenWidth, screenHeight, colour);
        drawMarker(renderer, knob, KNOB_SIZE, screenWidth, screenHeight, HANDLE_COLOUR, HANDLE_EDGE_COLOUR);

        // The eight resize handles.
        for (TransformHandle handle : TransformHandle.resizeHandles()) {
            Point2 at = mapping.toTarget(
                    transform.handlePosition(handle, SourceEditor.ROTATION_KNOB_DISTANCE));
            drawMarker(renderer, at, HANDLE_SIZE, screenWidth, screenHeight, HANDLE_COLOUR, HANDLE_EDGE_COLOUR);
        }
    }

    private static Point2 midpoint(Point2 a, Point2 b) {
        return new Point2((a.x() + b.x()) / 2.0, (a.y() + b.y()) / 2.0);
    }

    /** Draws a thick line as a quad perpendicular to its own direction. */
    private static void drawLine(GlQuadRenderer renderer, Point2 from, Point2 to, double thickness,
                                 int screenWidth, int screenHeight, int colour) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double length = Math.hypot(dx, dy);
        if (length < 1e-6) {
            return;
        }
        double nx = -dy / length * (thickness / 2.0);
        double ny = dx / length * (thickness / 2.0);
        Point2[] corners = {
                new Point2(from.x() + nx, from.y() + ny),
                new Point2(to.x() + nx, to.y() + ny),
                new Point2(to.x() - nx, to.y() - ny),
                new Point2(from.x() - nx, from.y() - ny)
        };
        renderer.drawSolidCorners(corners, screenWidth, screenHeight, colour);
    }

    /** A filled square with a light outline, so it reads against any background. */
    private static void drawMarker(GlQuadRenderer renderer, Point2 centre, double size,
                                   int screenWidth, int screenHeight, int fill, int edge) {
        drawSquare(renderer, centre, size + 2, screenWidth, screenHeight, edge);
        drawSquare(renderer, centre, size, screenWidth, screenHeight, fill);
    }

    private static void drawSquare(GlQuadRenderer renderer, Point2 centre, double size,
                                   int screenWidth, int screenHeight, int colour) {
        double half = size / 2.0;
        Point2[] corners = {
                new Point2(centre.x() - half, centre.y() - half),
                new Point2(centre.x() + half, centre.y() - half),
                new Point2(centre.x() + half, centre.y() + half),
                new Point2(centre.x() - half, centre.y() + half)
        };
        renderer.drawSolidCorners(corners, screenWidth, screenHeight, colour);
    }
}
