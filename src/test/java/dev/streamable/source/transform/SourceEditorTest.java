package dev.streamable.source.transform;

import dev.streamable.source.BrowserSource;
import dev.streamable.source.SourceList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.*;

/** Complete drag gestures through the editor, with no client involved. */
class SourceEditorTest {

    private SourceList sources;
    private SourceEditor editor;
    private BrowserSource source;

    @BeforeEach
    void setUp() {
        sources = new SourceList();
        source = BrowserSource.create("Alerts", "about:blank", 100, 100, 800, 600);
        sources.add(source);
        editor = new SourceEditor(sources);
        editor.setCanvas(1920, 1080);
        editor.setSnapping(0, false);   // snapping off so assertions are exact
    }

    @Test
    void clickingInsideSelectsAndDragMoves() {
        assertTrue(editor.onMousePress(new Point2(300, 300), false));
        assertEquals(source.id(), editor.selectedId());
        editor.onMouseDrag(new Point2(360, 340), false);
        editor.onMouseRelease();
        assertEquals(160, source.transform().x(), 1e-6);
        assertEquals(140, source.transform().y(), 1e-6);
    }

    @Test
    @DisplayName("the grab point stays under the cursor - the box does not jump")
    void dragKeepsGrabOffset() {
        editor.onMousePress(new Point2(150, 150), false);   // 50,50 inside the box
        editor.onMouseDrag(new Point2(950, 650), false);
        assertEquals(900, source.transform().x(), 1e-6);
        assertEquals(600, source.transform().y(), 1e-6);
    }

    @Test
    void clickingEmptySpaceClearsSelection() {
        editor.onMousePress(new Point2(300, 300), false);
        assertNotNull(editor.selectedId());
        assertFalse(editor.onMousePress(new Point2(1800, 1000), false));
        assertNull(editor.selectedId());
    }

    @Test
    void lockedSourcesCannotBeGrabbed() {
        source.setLocked(true);
        assertFalse(editor.onMousePress(new Point2(300, 300), false));
        assertNull(editor.selectedId());
    }

    @Test
    void cornerDragResizesProportionally() {
        editor.select(source.id());
        Point2 corner = source.transform().handlePosition(
                TransformHandle.BOTTOM_RIGHT, SourceEditor.ROTATION_KNOB_DISTANCE);
        assertTrue(editor.onMousePress(corner, false));
        editor.onMouseDrag(new Point2(corner.x() + 200, corner.y() + 40), false);
        editor.onMouseRelease();
        assertEquals(800.0 / 600.0, source.transform().aspectRatio(), 1e-9);
    }

    @Test
    @DisplayName("Shift while dragging a handle frees both axes")
    void shiftDragStretchesFreely() {
        editor.select(source.id());
        Point2 corner = source.transform().handlePosition(
                TransformHandle.BOTTOM_RIGHT, SourceEditor.ROTATION_KNOB_DISTANCE);
        editor.onMousePress(corner, true);
        editor.onMouseDrag(new Point2(1300, 520), true);
        editor.onMouseRelease();
        assertEquals(1200, source.transform().width(), 1e-6);
        assertEquals(420, source.transform().height(), 1e-6);
        assertNotEquals(800.0 / 600.0, source.transform().aspectRatio(), 1e-6);
    }

    @Test
    void rotationKnobRotatesAboutTheCentre() {
        editor.select(source.id());
        Point2 knob = editor.rotationKnobPosition();
        assertNotNull(knob);
        assertTrue(editor.onMousePress(knob, false));
        Point2 centre = source.transform().center();
        editor.onMouseDrag(new Point2(centre.x() + 400, centre.y()), false);
        editor.onMouseRelease();
        assertEquals(90.0, source.transform().rotation(), 1e-6);
    }

    @Test
    void rotationSnapsWithShift() {
        editor.select(source.id());
        editor.onMousePress(editor.rotationKnobPosition(), true);
        Point2 centre = source.transform().center();
        double radians = Math.toRadians(17.4 - 90);
        editor.onMouseDrag(new Point2(centre.x() + 400 * Math.cos(radians),
                centre.y() + 400 * Math.sin(radians)), true);
        assertEquals(15.0, source.transform().rotation(), 1e-6);
    }

    @Test
    void handlesRemainGrabbableAfterRotation() {
        editor.select(source.id());
        source.setTransform(source.transform().withRotation(37));
        for (TransformHandle handle : TransformHandle.resizeHandles()) {
            Point2 at = source.transform().handlePosition(handle, SourceEditor.ROTATION_KNOB_DISTANCE);
            assertEquals(handle, editor.hoveredHandle(at), "cannot grab " + handle + " when rotated");
        }
    }

    @Test
    void arrowKeysNudgeByOnePixelAndTenWithShift() {
        editor.select(source.id());
        assertTrue(editor.onArrowKey(GLFW.GLFW_KEY_RIGHT, false));
        assertEquals(101, source.transform().x(), 1e-6);
        assertTrue(editor.onArrowKey(GLFW.GLFW_KEY_DOWN, true));
        assertEquals(110, source.transform().y(), 1e-6);
        assertTrue(editor.onArrowKey(GLFW.GLFW_KEY_LEFT, false));
        assertEquals(100, source.transform().x(), 1e-6);
    }

    @Test
    void arrowKeysIgnoreNonArrowsAndLockedSources() {
        editor.select(source.id());
        assertFalse(editor.onArrowKey(GLFW.GLFW_KEY_A, false));
        source.setLocked(true);
        assertFalse(editor.onArrowKey(GLFW.GLFW_KEY_RIGHT, false));
    }

    @Test
    void snappingAlignsToCanvasCentreWhenEnabled() {
        editor.setSnapping(10, false);
        editor.onMousePress(new Point2(150, 150), false);   // grab offset (50, 50)
        // Drop the box at x=564 so its centre (964) lands 4 px past the canvas
        // centre (960) - inside the 10 px snap threshold.
        editor.onMouseDrag(new Point2(614, 190), false);
        editor.onMouseRelease();
        assertEquals(960.0, source.transform().x() + source.transform().width() / 2, 1e-6);
    }

    @Test
    void selectionPrefersTheTopmostSource() {
        BrowserSource above = BrowserSource.create("Chat", "about:blank", 100, 100, 800, 600);
        sources.add(above);
        editor.onMousePress(new Point2(300, 300), false);
        assertEquals(above.id(), editor.selectedId());
    }

    @Test
    void draggingIsReportedForCursorFeedback() {
        editor.select(source.id());
        assertFalse(editor.isDragging());
        editor.onMousePress(new Point2(300, 300), false);
        assertTrue(editor.isDragging());
        editor.onMouseRelease();
        assertFalse(editor.isDragging());
    }
}
