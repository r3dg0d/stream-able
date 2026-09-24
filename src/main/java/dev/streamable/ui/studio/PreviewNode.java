package dev.streamable.ui.studio;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.streamable.StreamAbleClient;
import dev.streamable.config.VideoSettings;
import dev.streamable.ui.kit.Painter;
import dev.streamable.ui.kit.Theme;
import dev.streamable.ui.kit.UiNode;
import dev.streamable.ui.kit.Widgets;
import dev.streamable.video.OutputTransform;
import dev.streamable.video.Resolution;

/**
 * Live program preview, at the canvas's true aspect ratio.
 *
 * <p>Overlays are drawn only in the Studio, never into any output:</p>
 * <ul>
 *   <li>safe-area guides - action safe (93%) and title safe (90%);</li>
 *   <li>output framing - when an output crops the canvas (Fill or Center
 *       Crop into a narrower shape), the region viewers actually see.</li>
 * </ul>
 */
final class PreviewNode extends UiNode {

    private final Studio studio;
    private final int maxHeight;

    PreviewNode(Studio studio, int maxHeight) {
        this.studio = studio;
        this.maxHeight = maxHeight;
    }

    @Override
    public int preferredHeight(int availableWidth) {
        Resolution canvas = studio.client().canvasResolution();
        int h = (int) Math.round(availableWidth / canvas.aspectRatio());
        return Math.clamp(h, 60, Math.max(60, maxHeight));
    }

    @Override
    protected void renderSelf(Painter p) {
        StreamAbleClient client = studio.client();
        Resolution canvas = client.canvasResolution();
        p.roundRect(x, y, width, height, Theme.RADIUS_LARGE, 0xFF050608);

        // Fit the canvas into the box without distortion.
        double scale = Math.min(width / (double) canvas.width(), height / (double) canvas.height());
        int pw = (int) Math.round(canvas.width() * scale);
        int ph = (int) Math.round(canvas.height() * scale);
        int px = x + (width - pw) / 2;
        int py = y + (height - ph) / 2;

        GpuTextureView view = client.compositor().previewView(false);
        if (view != null && client.compositor().isUsable()) {
            // The program target is rendered in GL orientation (row 0 at the bottom).
            p.texture(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                    px, py, px + pw, py + ph, 0f, 1f, 1f, 0f);
        } else {
            p.fill(px, py, pw, ph, 0xFF0C0E13);
            String message = client.compositor().isUsable()
                    ? "Composing the program canvas..."
                    : "The compositor could not start on this graphics driver; see the log.";
            p.textCentered(message, px + pw / 2f, py + ph / 2f - 4, Theme.TEXT_MUTED, Theme.TEXT_CAPTION,
                    Painter.Weight.REGULAR);
        }

        VideoSettings video = client.config().video;
        if (video.showSafeAreaGuides) {
            guide(p, px, py, pw, ph, 0.93f, 0x40FFFFFF);
            guide(p, px, py, pw, ph, 0.90f, 0x66FFC857);
            p.fill(px + pw / 2 - 4, py + ph / 2, 9, 1, 0x40FFFFFF);
            p.fill(px + pw / 2, py + ph / 2 - 4, 1, 9, 0x40FFFFFF);
        }
        framing(p, "Stream", client.streamingOutput(), video.streaming.effectiveMode(), canvas,
                px, py, scale, Theme.LIVE);
        framing(p, "Recording", client.recordingOutput(), video.recording.effectiveMode(), canvas,
                px, py, scale, Theme.RECORDING);

        String label = "PROGRAM " + canvas.label() + " · " + canvas.marketedRatio();
        Widgets.StatusPill.drawPill(p, x + 6, y + 6, label, Theme.TEXT_SECONDARY, false);
        if (client.video().stats().frozen()) {
            String note = "Game frozen for outputs while the Studio is open";
            int w = p.textWidth(note, Theme.TEXT_CAPTION, Painter.Weight.SEMIBOLD) + 18;
            Widgets.StatusPill.drawPill(p, x + width - w - 6, y + 6, note, Theme.INFO, false);
        }
    }

    private static void guide(Painter p, int px, int py, int pw, int ph, float fraction, int color) {
        int w = Math.round(pw * fraction);
        int h = Math.round(ph * fraction);
        p.roundBorder(px + (pw - w) / 2f, py + (ph - h) / 2f, w, h, 1, 0.75f, color);
    }

    /** Outlines the part of the canvas an output shows, when it does not show all of it. */
    private static void framing(Painter p, String name, Resolution output, dev.streamable.video.ScalingMode mode,
                                Resolution canvas, int px, int py, double scale, int color) {
        OutputTransform t = OutputTransform.compute(canvas, output, mode);
        if (!t.cropsSource()) {
            return;
        }
        float fx = (float) (px + t.srcX() * scale);
        float fy = (float) (py + t.srcY() * scale);
        float fw = (float) (t.srcW() * scale);
        float fh = (float) (t.srcH() * scale);
        p.roundBorder(fx, fy, fw, fh, 1, 1.25f, Theme.withAlpha(color, 0.9f));
        String label = name + " " + output.label() + " · " + mode.displayName();
        int lw = p.textWidth(label, Theme.TEXT_CAPTION, Painter.Weight.SEMIBOLD);
        p.roundRect(fx + 2, fy + fh - 12, lw + 6, 10, Theme.RADIUS_SMALL, 0xCC000000);
        p.text(label, fx + 5, fy + fh - 10, color, Theme.TEXT_CAPTION, Painter.Weight.SEMIBOLD);
    }
}
