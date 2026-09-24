package dev.streamable.ui.kit;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

/** One rounded rectangle submitted to the GUI render state. */
public record RoundedRectState(Matrix3x2f pose, float x0, float y0, float x1, float y1, int color,
                               float radius, float border, float softness, ScreenRectangle scissorArea,
                               ScreenRectangle bounds) implements GuiElementRenderState {

    public static RoundedRectState of(Matrix3x2f pose, float x0, float y0, float x1, float y1, int color,
                                      float radius, float border, float softness, ScreenRectangle scissor) {
        float pad = softness;
        Vector2f a = pose.transformPosition(new Vector2f(x0 - pad, y0 - pad));
        Vector2f b = pose.transformPosition(new Vector2f(x1 + pad, y1 + pad));
        Vector2f c = pose.transformPosition(new Vector2f(x0 - pad, y1 + pad));
        Vector2f d = pose.transformPosition(new Vector2f(x1 + pad, y0 - pad));
        int minX = (int) Math.floor(Math.min(Math.min(a.x, b.x), Math.min(c.x, d.x)));
        int minY = (int) Math.floor(Math.min(Math.min(a.y, b.y), Math.min(c.y, d.y)));
        int maxX = (int) Math.ceil(Math.max(Math.max(a.x, b.x), Math.max(c.x, d.x)));
        int maxY = (int) Math.ceil(Math.max(Math.max(a.y, b.y), Math.max(c.y, d.y)));
        ScreenRectangle bounds = new ScreenRectangle(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
        if (scissor != null) {
            bounds = scissor.intersection(bounds);
        }
        return new RoundedRectState(pose, x0, y0, x1, y1, color, radius, border, softness, scissor, bounds);
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        float pad = softness;
        float cx = (x0 + x1) / 2f;
        float cy = (y0 + y1) / 2f;
        int hw = Math.round((x1 - x0) / 2f * 16f);
        int hh = Math.round((y1 - y0) / 2f * 16f);
        int r = Math.round(radius * 16f);
        int mode = softness > 0 ? 16384 + Math.round(softness * 16f) : Math.round(border * 16f);
        vertex(consumer, x0 - pad, y0 - pad, cx, cy, hw, hh, r, mode);
        vertex(consumer, x0 - pad, y1 + pad, cx, cy, hw, hh, r, mode);
        vertex(consumer, x1 + pad, y1 + pad, cx, cy, hw, hh, r, mode);
        vertex(consumer, x1 + pad, y0 - pad, cx, cy, hw, hh, r, mode);
    }

    private void vertex(VertexConsumer consumer, float x, float y, float cx, float cy, int hw, int hh, int r, int mode) {
        consumer.addVertexWith2DPose(pose, x, y).setColor(color).setUv(x - cx, y - cy).setUv1(hw, hh).setUv2(r, mode);
    }

    @Override
    public RenderPipeline pipeline() {
        return UiPipelines.ROUNDED_RECT;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    @Override
    public ScreenRectangle scissorArea() {
        return scissorArea;
    }
}
