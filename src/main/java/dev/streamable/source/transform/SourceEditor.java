package dev.streamable.source.transform;

import dev.streamable.source.BrowserSource;
import dev.streamable.source.SourceList;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Interaction state for the OBS-style transform editor.
 *
 * <p>Deliberately free of Minecraft and OpenGL types: it takes canvas-space
 * points and GLFW key codes and mutates source transforms, which makes complete
 * drag gestures unit-testable without a client.</p>
 *
 * <p>Gestures follow the conventions the user asked for:</p>
 * <ul>
 *   <li>drag inside the box - move</li>
 *   <li>drag a red handle - resize, preserving aspect ratio</li>
 *   <li><b>Shift</b> + drag a handle - free, non-uniform stretch</li>
 *   <li>drag the round knob above the box - rotate ({@code Shift} snaps to 15 degrees)</li>
 *   <li>arrow keys nudge by 1 canvas pixel, {@code Shift} + arrows by 10</li>
 * </ul>
 */
public final class SourceEditor {

    /** Picking radius around a handle, in canvas pixels. */
    public static final double HANDLE_RADIUS = 9.0;
    /** Distance of the rotation knob above the top edge, in canvas pixels. */
    public static final double ROTATION_KNOB_DISTANCE = 34.0;

    private enum Drag { NONE, MOVE, RESIZE, ROTATE }

    private final SourceList sources;

    private UUID selectedId;
    private Drag drag = Drag.NONE;
    private TransformHandle activeHandle;
    private SourceTransform transformAtDragStart;
    private Point2 grabOffset = Point2.ZERO;

    private int canvasWidth = 1920;
    private int canvasHeight = 1080;
    private double snapThreshold = 8.0;
    private boolean snapToOtherSources = true;

    public SourceEditor(SourceList sources) {
        this.sources = sources;
    }

    public void setCanvas(int width, int height) {
        this.canvasWidth = Math.max(1, width);
        this.canvasHeight = Math.max(1, height);
    }

    public void setSnapping(double threshold, boolean includeOtherSources) {
        this.snapThreshold = Math.max(0, threshold);
        this.snapToOtherSources = includeOtherSources;
    }

    public UUID selectedId() {
        return selectedId;
    }

    public BrowserSource selected() {
        return selectedId == null ? null : sources.byId(selectedId).orElse(null);
    }

    public void select(UUID id) {
        this.selectedId = id;
        cancelDrag();
    }

    public void clearSelection() {
        selectedId = null;
        cancelDrag();
    }

    public boolean isDragging() {
        return drag != Drag.NONE;
    }

    /** The handle currently under the cursor, for cursor feedback. */
    public TransformHandle hoveredHandle(Point2 canvasPoint) {
        BrowserSource source = selected();
        if (source == null) {
            return null;
        }
        return TransformMath.pickHandle(source.transform(), canvasPoint,
                HANDLE_RADIUS, ROTATION_KNOB_DISTANCE);
    }

    /**
     * Handles a mouse press in canvas coordinates.
     *
     * @return {@code true} when the editor consumed the click
     */
    public boolean onMousePress(Point2 canvasPoint, boolean shiftHeld) {
        BrowserSource current = selected();

        // A handle of the current selection wins over selecting something else,
        // because handles sit on and outside the edge of the box.
        if (current != null && !current.locked()) {
            TransformHandle handle = TransformMath.pickHandle(current.transform(), canvasPoint,
                    HANDLE_RADIUS, ROTATION_KNOB_DISTANCE);
            if (handle != null) {
                activeHandle = handle;
                transformAtDragStart = current.transform();
                drag = handle.isRotation() ? Drag.ROTATE : Drag.RESIZE;
                return true;
            }
            if (current.transform().contains(canvasPoint)) {
                beginMove(current, canvasPoint);
                return true;
            }
        }

        BrowserSource picked = sources.pickTopmostAt(canvasPoint);
        if (picked == null) {
            clearSelection();
            return false;
        }
        selectedId = picked.id();
        beginMove(picked, canvasPoint);
        return true;
    }

    private void beginMove(BrowserSource source, Point2 canvasPoint) {
        drag = Drag.MOVE;
        activeHandle = null;
        transformAtDragStart = source.transform();
        // Remember where inside the box the grab happened so the source does not
        // jump so its corner snaps to the cursor.
        grabOffset = new Point2(canvasPoint.x() - transformAtDragStart.x(),
                canvasPoint.y() - transformAtDragStart.y());
    }

    /** Updates the drag. Call on every mouse move while a button is held. */
    public void onMouseDrag(Point2 canvasPoint, boolean shiftHeld) {
        BrowserSource source = selected();
        if (source == null || drag == Drag.NONE || transformAtDragStart == null) {
            return;
        }
        switch (drag) {
            case MOVE -> {
                SourceTransform moved = transformAtDragStart.withPosition(
                        canvasPoint.x() - grabOffset.x(), canvasPoint.y() - grabOffset.y());
                source.setTransform(TransformMath.snap(moved, canvasWidth, canvasHeight,
                        otherTransforms(source), snapThreshold));
            }
            // Default drag preserves aspect ratio; Shift frees both axes.
            case RESIZE -> source.setTransform(
                    TransformMath.resize(transformAtDragStart, activeHandle, canvasPoint, shiftHeld));
            case ROTATE -> source.setTransform(
                    TransformMath.rotate(transformAtDragStart, canvasPoint, shiftHeld));
            case NONE -> { }
        }
    }

    public void onMouseRelease() {
        cancelDrag();
    }

    private void cancelDrag() {
        drag = Drag.NONE;
        activeHandle = null;
        transformAtDragStart = null;
        grabOffset = Point2.ZERO;
    }

    private List<SourceTransform> otherTransforms(BrowserSource exclude) {
        if (!snapToOtherSources) {
            return List.of();
        }
        List<SourceTransform> others = new ArrayList<>();
        for (BrowserSource source : sources.snapshot()) {
            if (!source.id().equals(exclude.id()) && source.visible()) {
                others.add(source.transform());
            }
        }
        return others;
    }

    /**
     * Nudges the selection with the arrow keys.
     *
     * @param glfwKey    GLFW key code
     * @param shiftHeld  {@code true} for the 10 px step
     * @return {@code true} when the key was consumed
     */
    public boolean onArrowKey(int glfwKey, boolean shiftHeld) {
        BrowserSource source = selected();
        if (source == null || source.locked()) {
            return false;
        }
        double step = shiftHeld ? 10.0 : 1.0;
        double dx = 0;
        double dy = 0;
        switch (glfwKey) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT -> dx = -step;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT -> dx = step;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> dy = -step;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> dy = step;
            default -> {
                return false;
            }
        }
        source.setTransform(source.transform().translated(dx, dy));
        return true;
    }

    /** Canvas positions of the selection's handles, for the renderer. */
    public Point2[] handlePositions() {
        BrowserSource source = selected();
        if (source == null) {
            return new Point2[0];
        }
        TransformHandle[] handles = TransformHandle.resizeHandles();
        Point2[] positions = new Point2[handles.length];
        for (int i = 0; i < handles.length; i++) {
            positions[i] = source.transform().handlePosition(handles[i], ROTATION_KNOB_DISTANCE);
        }
        return positions;
    }

    /** Canvas position of the rotation knob, or {@code null} with no selection. */
    public Point2 rotationKnobPosition() {
        BrowserSource source = selected();
        return source == null ? null
                : source.transform().handlePosition(TransformHandle.ROTATION, ROTATION_KNOB_DISTANCE);
    }
}
