package dev.streamable.config;

/** Overlay, HUD and source-editor preferences. */
public final class InterfaceSettings {

    /** Shared by {@link #validate()} and the Studio HUD and editor sliders. */
    public static final double MIN_SNAP_PX = 0;
    public static final double MAX_SNAP_PX = 64;
    public static final float MIN_HUD_SCALE = 0.5f;
    public static final float MAX_HUD_SCALE = 3.0f;
    public static final float MIN_HUD_OPACITY = 0.1f;
    public static final float MAX_HUD_OPACITY = 1.0f;

    /** Stream health HUD. Distinct from a browser source - this is Stream-able's own. */
    public boolean showStreamHud = true;
    public boolean detailedStreamHud = false;
    /**
     * Corner when free-drag is unset: 0 = top-left, 1 = top-right,
     * 2 = bottom-left, 3 = bottom-right. Ignored once the HUD has been dragged
     * (streamHudX/Y &gt;= 0); Reset position or picking a corner clears the drag.
     */
    public int streamHudPosition = 1;
    public float streamHudScale = 1.0f;
    public float streamHudOpacity = 0.85f;
    /** HUD position as a fraction of the free screen area; negative means the default top-left corner, clear of toasts. */
    public float streamHudX = -1;
    public float streamHudY = -1;

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
        // NaN survives Math.clamp; restore defaults before clamping so a hostile
        // JSON edit cannot leave the HUD at an unusable scale or opacity.
        if (!Float.isFinite(streamHudScale)) {
            streamHudScale = 1.0f;
        }
        streamHudScale = (float) Math.clamp(streamHudScale, MIN_HUD_SCALE, MAX_HUD_SCALE);
        if (!Float.isFinite(streamHudOpacity)) {
            streamHudOpacity = 0.85f;
        }
        streamHudOpacity = (float) Math.clamp(streamHudOpacity, MIN_HUD_OPACITY, MAX_HUD_OPACITY);
        if (!Float.isFinite(streamHudX) || streamHudX > 1) {
            streamHudX = -1;
        }
        if (!Float.isFinite(streamHudY) || streamHudY > 1) {
            streamHudY = -1;
        }
        canvasWidth = Math.clamp(canvasWidth - (canvasWidth % 2), 320, 16384);
        canvasHeight = Math.clamp(canvasHeight - (canvasHeight % 2), 240, 16384);
        if (!Double.isFinite(snapThreshold)) {
            snapThreshold = 8.0;
        }
        snapThreshold = Math.clamp(snapThreshold, MIN_SNAP_PX, MAX_SNAP_PX);
    }
}
