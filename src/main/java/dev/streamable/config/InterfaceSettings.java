package dev.streamable.config;

/** Overlay, HUD and source-editor preferences. */
public final class InterfaceSettings {

    /** Stream health HUD. Distinct from a browser source - this is Stream-able's own. */
    public boolean showStreamHud = true;
    public boolean detailedStreamHud = false;
    /** 0 = top-left, 1 = top-right, 2 = bottom-left, 3 = bottom-right. */
    public int streamHudPosition = 1;
    public float streamHudScale = 1.0f;
    public float streamHudOpacity = 0.85f;

    /** Program canvas the source transforms are expressed in. */
    public int canvasWidth = 1920;
    public int canvasHeight = 1080;

    /** Snap distance in canvas pixels while dragging; 0 disables snapping. */
    public double snapThreshold = 8.0;
    public boolean snapToOtherSources = true;
    /** Chromium normally produces premultiplied alpha; exposed for odd CEF builds. */
    public boolean premultipliedBrowserAlpha = true;

    public void validate() {
        streamHudPosition = Math.clamp(streamHudPosition, 0, 3);
        streamHudScale = (float) Math.clamp(streamHudScale, 0.5, 3.0);
        streamHudOpacity = (float) Math.clamp(streamHudOpacity, 0.1, 1.0);
        canvasWidth = Math.clamp(canvasWidth - (canvasWidth % 2), 320, 16384);
        canvasHeight = Math.clamp(canvasHeight - (canvasHeight % 2), 240, 16384);
        snapThreshold = Math.clamp(snapThreshold, 0.0, 64.0);
    }
}
