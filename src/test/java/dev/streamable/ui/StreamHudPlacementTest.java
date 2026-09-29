package dev.streamable.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamHudPlacementTest {

    private static final int MARGIN = 6;
    private static final int DEFAULT_TOP_Y = 34;
    private static final int PANEL_W = 120;
    private static final int PANEL_H = 40;
    private static final int GUI_W = 1920;
    private static final int GUI_H = 1080;

    private static StreamHudPlacement.Point corner(int position) {
        return StreamHudPlacement.resolve(position, -1, -1, PANEL_W, PANEL_H, GUI_W, GUI_H, MARGIN, DEFAULT_TOP_Y);
    }

    @Test
    void topLeftIsDefaultCornerZero() {
        StreamHudPlacement.Point p = corner(0);
        assertEquals(MARGIN, p.x());
        assertEquals(DEFAULT_TOP_Y, p.y());
    }

    @Test
    void topRightUsesFullFreeWidth() {
        int freeW = GUI_W - PANEL_W - 2 * MARGIN;
        StreamHudPlacement.Point p = corner(1);
        assertEquals(MARGIN + freeW, p.x());
        assertEquals(DEFAULT_TOP_Y, p.y());
    }

    @Test
    void bottomLeftAndRightSitOnFreeHeight() {
        int freeW = GUI_W - PANEL_W - 2 * MARGIN;
        int freeH = GUI_H - PANEL_H - 2 * MARGIN;
        assertEquals(new StreamHudPlacement.Point(MARGIN, MARGIN + freeH), corner(2));
        assertEquals(new StreamHudPlacement.Point(MARGIN + freeW, MARGIN + freeH), corner(3));
    }

    @Test
    void freeDragOverridesCorner() {
        StreamHudPlacement.Point p = StreamHudPlacement.resolve(
                1, 0.5f, 0.25f, PANEL_W, PANEL_H, GUI_W, GUI_H, MARGIN, DEFAULT_TOP_Y);
        int freeW = GUI_W - PANEL_W - 2 * MARGIN;
        int freeH = GUI_H - PANEL_H - 2 * MARGIN;
        assertEquals(MARGIN + Math.round(freeW * 0.5f), p.x());
        assertEquals(MARGIN + Math.round(freeH * 0.25f), p.y());
    }

    @Test
    void partialFreeDragFallsBackToCorner() {
        // Only X set — treat as unplaced so the corner enum still applies.
        StreamHudPlacement.Point p = StreamHudPlacement.resolve(
                1, 0.5f, -1f, PANEL_W, PANEL_H, GUI_W, GUI_H, MARGIN, DEFAULT_TOP_Y);
        int freeW = GUI_W - PANEL_W - 2 * MARGIN;
        assertEquals(MARGIN + freeW, p.x());
        assertEquals(DEFAULT_TOP_Y, p.y());
    }

    @Test
    void hostilePositionClampsToValidCorner() {
        assertEquals(corner(0), StreamHudPlacement.resolve(
                -9, -1, -1, PANEL_W, PANEL_H, GUI_W, GUI_H, MARGIN, DEFAULT_TOP_Y));
        assertEquals(corner(3), StreamHudPlacement.resolve(
                99, -1, -1, PANEL_W, PANEL_H, GUI_W, GUI_H, MARGIN, DEFAULT_TOP_Y));
    }

    @Test
    void nanFreeDragFallsBackToCorner() {
        StreamHudPlacement.Point p = StreamHudPlacement.resolve(
                0, Float.NaN, Float.NaN, PANEL_W, PANEL_H, GUI_W, GUI_H, MARGIN, DEFAULT_TOP_Y);
        assertEquals(MARGIN, p.x());
        assertEquals(DEFAULT_TOP_Y, p.y());
    }
}
