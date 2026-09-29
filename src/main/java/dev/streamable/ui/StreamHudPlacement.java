package dev.streamable.ui;

/**
 * Resolves where the Stream HUD sits on screen.
 *
 * <p>Free-drag fractions ({@code streamHudX}/{@code streamHudY}) win when both
 * are set. Otherwise the corner enum ({@code streamHudPosition}: 0 = top-left,
 * 1 = top-right, 2 = bottom-left, 3 = bottom-right) places the panel. Pure
 * inputs so unit tests do not need Minecraft.</p>
 */
public final class StreamHudPlacement {

    /** Top-left pixel of the scaled HUD panel. */
    public record Point(int x, int y) {
    }

    private StreamHudPlacement() {
    }

    /**
     * @param position     corner when free-drag is unset (clamped 0–3)
     * @param freeDragX    fraction of free width, or &lt; 0 for corner default
     * @param freeDragY    fraction of free height, or &lt; 0 for corner default
     * @param panelWidth   scaled HUD width in GUI pixels
     * @param panelHeight  scaled HUD height in GUI pixels
     * @param guiWidth     full GUI width
     * @param guiHeight    full GUI height
     * @param margin       inset from each screen edge
     * @param defaultTopY  preferred Y for top corners (clear of toasts / editor chrome)
     */
    public static Point resolve(int position, float freeDragX, float freeDragY,
                                int panelWidth, int panelHeight,
                                int guiWidth, int guiHeight,
                                int margin, int defaultTopY) {
        int freeW = Math.max(0, guiWidth - panelWidth - 2 * margin);
        int freeH = Math.max(0, guiHeight - panelHeight - 2 * margin);
        if (freeDragX >= 0 && freeDragY >= 0
                && Float.isFinite(freeDragX) && Float.isFinite(freeDragY)) {
            float fx = Math.clamp(freeDragX, 0f, 1f);
            float fy = Math.clamp(freeDragY, 0f, 1f);
            return new Point(margin + Math.round(freeW * fx), margin + Math.round(freeH * fy));
        }
        int pos = Math.clamp(position, 0, 3);
        boolean right = pos == 1 || pos == 3;
        boolean bottom = pos == 2 || pos == 3;
        int x = right ? margin + freeW : margin;
        int y = bottom ? margin + freeH : Math.min(Math.max(0, defaultTopY), margin + freeH);
        return new Point(x, y);
    }
}
