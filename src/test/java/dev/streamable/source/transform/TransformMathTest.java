package dev.streamable.source.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransformMathTest {

    private static final double EPS = 1e-6;

    private static void assertPoint(Point2 expected, Point2 actual) {
        assertAll(
                () -> assertEquals(expected.x(), actual.x(), 1e-6, "x"),
                () -> assertEquals(expected.y(), actual.y(), 1e-6, "y"));
    }

    @Nested
    @DisplayName("forward / inverse mapping")
    class Mapping {

        @Test
        void identityWhenUnrotated() {
            SourceTransform t = SourceTransform.of(100, 50, 800, 600);
            assertPoint(new Point2(100, 50), t.localToCanvas(new Point2(0, 0)));
            assertPoint(new Point2(900, 650), t.localToCanvas(new Point2(800, 600)));
        }

        @Test
        void inverseUndoesForwardForArbitraryTransform() {
            SourceTransform t = new SourceTransform(137, -42, 913, 411, 37.4);
            for (Point2 local : List.of(new Point2(0, 0), new Point2(913, 0),
                    new Point2(913, 411), new Point2(0, 411), new Point2(455.5, 205.5))) {
                assertPoint(local, t.canvasToLocal(t.localToCanvas(local)));
            }
        }

        @Test
        void rotationIsAboutTheCentre() {
            SourceTransform t = new SourceTransform(0, 0, 100, 100, 90);
            assertPoint(t.center(), t.localToCanvas(new Point2(50, 50)));
            // top-left corner rotates a quarter turn clockwise to the top-right
            assertPoint(new Point2(100, 0), t.localToCanvas(new Point2(0, 0)));
        }

        @Test
        void containsRespectsRotation() {
            SourceTransform t = new SourceTransform(0, 0, 200, 40, 45);
            assertTrue(t.contains(t.center()));
            // A corner of the *unrotated* box is outside the rotated one.
            assertFalse(t.contains(new Point2(199, 1)));
        }

        @Test
        void clickInsideRotatedStretchedSourceMapsToCorrectLocalPixel() {
            // Requirement: a button inside a moved, stretched, 37-degree-rotated
            // browser source must still receive the click at the right spot.
            SourceTransform t = new SourceTransform(640, 360, 1200, 420, 37.0);
            Point2 buttonLocal = new Point2(900, 310);
            Point2 canvas = t.localToCanvas(buttonLocal);
            assertPoint(buttonLocal, t.canvasToLocal(canvas));
        }
    }

    @Nested
    @DisplayName("resize")
    class Resize {

        @Test
        void proportionalCornerDragKeepsAspectRatio() {
            SourceTransform t = SourceTransform.of(0, 0, 800, 600);
            SourceTransform r = TransformMath.resize(t, TransformHandle.BOTTOM_RIGHT, new Point2(1000, 700), false);
            assertEquals(t.aspectRatio(), r.aspectRatio(), 1e-9);
            assertEquals(1000, r.width(), EPS);
            assertEquals(750, r.height(), EPS);
        }

        @Test
        void shiftDragAllowsNonUniformStretch() {
            SourceTransform t = SourceTransform.of(0, 0, 800, 600);
            SourceTransform r = TransformMath.resize(t, TransformHandle.BOTTOM_RIGHT, new Point2(1200, 420), true);
            assertEquals(1200, r.width(), EPS);
            assertEquals(420, r.height(), EPS);
        }

        @Test
        void anchorStaysFixedWhenUnrotated() {
            SourceTransform t = SourceTransform.of(100, 100, 400, 300);
            Point2 anchorBefore = t.handlePosition(TransformHandle.TOP_LEFT, 0);
            SourceTransform r = TransformMath.resize(t, TransformHandle.BOTTOM_RIGHT, new Point2(900, 800), true);
            assertPoint(anchorBefore, r.handlePosition(TransformHandle.TOP_LEFT, 0));
        }

        @Test
        void anchorStaysFixedWhenRotated() {
            SourceTransform t = new SourceTransform(100, 100, 400, 300, 37.0);
            Point2 anchorBefore = t.handlePosition(TransformHandle.TOP_LEFT, 0);
            Point2 mouse = t.localToCanvas(new Point2(700, 500));
            SourceTransform r = TransformMath.resize(t, TransformHandle.BOTTOM_RIGHT, mouse, true);
            assertPoint(anchorBefore, r.handlePosition(TransformHandle.TOP_LEFT, 0));
            assertEquals(37.0, r.rotation(), EPS, "rotation must be preserved");
        }

        @Test
        void edgeHandleOnlyChangesOneAxisInFreeMode() {
            SourceTransform t = SourceTransform.of(0, 0, 400, 300);
            SourceTransform r = TransformMath.resize(t, TransformHandle.MIDDLE_RIGHT, new Point2(600, 999), true);
            assertEquals(600, r.width(), EPS);
            assertEquals(300, r.height(), EPS);
        }

        @Test
        void draggingPastAnchorClampsInsteadOfFlipping() {
            SourceTransform t = SourceTransform.of(0, 0, 400, 300);
            SourceTransform r = TransformMath.resize(t, TransformHandle.BOTTOM_RIGHT, new Point2(-5000, -5000), true);
            assertEquals(SourceTransform.MIN_SIZE, r.width(), EPS);
            assertEquals(SourceTransform.MIN_SIZE, r.height(), EPS);
            assertTrue(r.width() > 0 && r.height() > 0);
        }

        @Test
        void neverProducesNaNOrNegativeDimensions() {
            SourceTransform t = SourceTransform.of(0, 0, 400, 300);
            for (TransformHandle h : TransformHandle.resizeHandles()) {
                for (Point2 p : List.of(new Point2(Double.NaN, 10), new Point2(1e12, 1e12),
                        new Point2(-1e12, 0), new Point2(0, 0))) {
                    SourceTransform r = TransformMath.resize(t, h, p, true);
                    assertFalse(Double.isNaN(r.width()) || Double.isNaN(r.height()), "NaN size from " + h);
                    assertTrue(r.width() >= SourceTransform.MIN_SIZE, "width underflow from " + h);
                    assertTrue(r.height() >= SourceTransform.MIN_SIZE, "height underflow from " + h);
                    assertTrue(r.width() <= SourceTransform.MAX_SIZE, "width overflow from " + h);
                }
            }
        }
    }

    @Nested
    @DisplayName("rotation")
    class Rotate {

        @Test
        void knobAboveCentreMeansZeroDegrees() {
            SourceTransform t = SourceTransform.of(0, 0, 200, 200);
            SourceTransform r = TransformMath.rotate(t, new Point2(100, -50), false);
            assertEquals(0.0, r.rotation(), 1e-9);
        }

        @Test
        void draggingKnobToTheRightIsQuarterTurn() {
            SourceTransform t = SourceTransform.of(0, 0, 200, 200);
            SourceTransform r = TransformMath.rotate(t, new Point2(500, 100), false);
            assertEquals(90.0, r.rotation(), 1e-9);
        }

        @Test
        void arbitraryAnglesAreSupported() {
            SourceTransform t = SourceTransform.of(0, 0, 100, 100);
            for (double angle : new double[]{17.4, 183.2, 359.0}) {
                double rad = Math.toRadians(angle - 90);
                Point2 mouse = new Point2(50 + 80 * Math.cos(rad), 50 + 80 * Math.sin(rad));
                assertEquals(angle, TransformMath.rotate(t, mouse, false).rotation(), 1e-6);
            }
        }

        @Test
        void snapRoundsTo15Degrees() {
            SourceTransform t = SourceTransform.of(0, 0, 100, 100);
            double rad = Math.toRadians(17.4 - 90);
            Point2 mouse = new Point2(50 + 80 * Math.cos(rad), 50 + 80 * Math.sin(rad));
            assertEquals(15.0, TransformMath.rotate(t, mouse, true).rotation(), 1e-6);
        }

        @Test
        void normalisationKeepsAnglesInRange() {
            assertEquals(10.0, SourceTransform.normaliseDegrees(370.0), EPS);
            assertEquals(350.0, SourceTransform.normaliseDegrees(-10.0), EPS);
            assertEquals(0.0, SourceTransform.normaliseDegrees(Double.NaN), EPS);
        }
    }

    @Nested
    @DisplayName("handle picking and snapping")
    class Picking {

        @Test
        void picksRotationKnobOverCorner() {
            SourceTransform t = SourceTransform.of(0, 0, 200, 200);
            Point2 knob = t.handlePosition(TransformHandle.ROTATION, 30);
            assertEquals(TransformHandle.ROTATION, TransformMath.pickHandle(t, knob, 8, 30));
        }

        @Test
        void picksCornerHandles() {
            SourceTransform t = new SourceTransform(0, 0, 200, 200, 25);
            for (TransformHandle h : TransformHandle.resizeHandles()) {
                Point2 at = t.handlePosition(h, 30);
                assertEquals(h, TransformMath.pickHandle(t, at, 6, 30), "failed to pick " + h);
            }
        }

        @Test
        void returnsNullAwayFromHandles() {
            SourceTransform t = SourceTransform.of(0, 0, 200, 200);
            assertNull(TransformMath.pickHandle(t, new Point2(100, 100), 6, 30));
        }

        @Test
        void snapsBoxCentreToCanvasCentre() {
            // Centre sits at 956, four pixels short of the 960 canvas centre,
            // and is the closest guide match of the three candidate edges.
            SourceTransform t = SourceTransform.of(906, 100, 100, 100);
            SourceTransform snapped = TransformMath.snap(t, 1920, 1080, List.of(), 8);
            assertEquals(960.0, snapped.x() + snapped.width() / 2, 1e-6);
        }

        @Test
        void snapsLeadingEdgeToCanvasEdge() {
            SourceTransform t = SourceTransform.of(3, 500, 100, 100);
            SourceTransform snapped = TransformMath.snap(t, 1920, 1080, List.of(), 8);
            assertEquals(0.0, snapped.x(), 1e-6);
        }

        @Test
        void snapsToAnotherSourceEdge() {
            SourceTransform other = SourceTransform.of(400, 400, 200, 200);
            SourceTransform t = SourceTransform.of(603, 700, 100, 100);
            SourceTransform snapped = TransformMath.snap(t, 1920, 1080, List.of(other), 8);
            assertEquals(600.0, snapped.x(), 1e-6, "should snap to the other source's right edge");
        }

        @Test
        void snappingCanBeDisabled() {
            SourceTransform t = SourceTransform.of(906, 100, 100, 100);
            SourceTransform unsnapped = TransformMath.snap(t, 1920, 1080, List.of(), 0);
            assertEquals(t.x(), unsnapped.x(), EPS, "threshold 0 must disable snapping");
        }

        @Test
        void doesNotSnapRotatedSources() {
            SourceTransform t = new SourceTransform(957, 100, 100, 100, 30);
            assertEquals(t.x(), TransformMath.snap(t, 1920, 1080, List.of(), 8).x(), EPS);
        }
    }
}
