package dev.streamable.ui.kit;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

/**
 * Drawing API for the Studio. Wraps Minecraft's GUI graphics with rounded
 * shapes, the Inter typeface, a text scale and clipping, so components never
 * touch low-level calls directly.
 */
public final class Painter {

    public enum Weight { REGULAR, SEMIBOLD }

    private static final FontDescription REGULAR =
            new FontDescription.Resource(Identifier.fromNamespaceAndPath("streamable", "ui"));
    private static final FontDescription SEMIBOLD =
            new FontDescription.Resource(Identifier.fromNamespaceAndPath("streamable", "ui_bold"));

    private final GuiGraphicsExtractor graphics;
    private final Font font;
    private final int mouseX;
    private final int mouseY;
    private final float delta;

    public Painter(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaSeconds) {
        this.graphics = graphics;
        this.font = Minecraft.getInstance().font;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.delta = deltaSeconds;
    }

    public GuiGraphicsExtractor graphics() {
        return graphics;
    }

    public int mouseX() {
        return mouseX;
    }

    public int mouseY() {
        return mouseY;
    }

    /** Seconds since the previous frame, for animations. */
    public float delta() {
        return delta;
    }

    // ---- shapes -----------------------------------------------------------------------

    public void fill(int x, int y, int w, int h, int color) {
        if (w > 0 && h > 0 && (color >>> 24) != 0) {
            graphics.fill(x, y, x + w, y + h, color);
        }
    }

    public void roundRect(float x, float y, float w, float h, float radius, int color) {
        submit(x, y, w, h, radius, 0, 0, color);
    }

    public void roundBorder(float x, float y, float w, float h, float radius, float width, int color) {
        submit(x, y, w, h, radius, Math.max(0.5f, width), 0, color);
    }

    /** A soft drop shadow under a panel. */
    public void shadow(float x, float y, float w, float h, float radius, float softness, int color) {
        submit(x, y + softness * 0.35f, w, h, radius, 0, softness, color);
    }

    public void circle(float cx, float cy, float r, int color) {
        submit(cx - r, cy - r, 2 * r, 2 * r, r, 0, 0, color);
    }

    private void submit(float x, float y, float w, float h, float radius, float border, float softness, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        graphics.guiRenderState.addGuiElement(RoundedRectState.of(new Matrix3x2f(graphics.pose()),
                x, y, x + w, y + h, color, radius, border, softness, graphics.scissorStack.peek()));
    }

    /** A thin line of any angle, drawn as a rotated rounded bar. */
    public void line(float x0, float y0, float x1, float y1, float width, int color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float length = (float) Math.hypot(dx, dy);
        if (length < 0.01f) {
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(x0, y0);
        graphics.pose().rotate((float) Math.atan2(dy, dx));
        submit(0, -width / 2f, length, width, width / 2f, 0, 0, color);
        graphics.pose().popMatrix();
    }

    // ---- text -------------------------------------------------------------------------

    public static Component styled(String text, Weight weight) {
        return Component.literal(text).withStyle(Style.EMPTY.withFont(weight == Weight.SEMIBOLD ? SEMIBOLD : REGULAR));
    }

    public int textWidth(String text, float scale, Weight weight) {
        return Math.round(font.width(styled(text, weight)) * scale);
    }

    public int textWidth(String text) {
        return textWidth(text, Theme.TEXT_BODY, Weight.REGULAR);
    }

    public int lineHeight(float scale) {
        return Math.round(9 * scale);
    }

    public void text(String text, float x, float y, int color) {
        text(text, x, y, color, Theme.TEXT_BODY, Weight.REGULAR);
    }

    public void text(String text, float x, float y, int color, float scale, Weight weight) {
        if (text == null || text.isEmpty() || (color >>> 24) == 0) {
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        if (scale != 1f) {
            graphics.pose().scale(scale, scale);
        }
        graphics.text(font, styled(text, weight), 0, 0, color, false);
        graphics.pose().popMatrix();
    }

    /** Draws text truncated with an ellipsis to fit {@code maxWidth}. */
    public void textClipped(String text, float x, float y, int maxWidth, int color, float scale, Weight weight) {
        text(ellipsize(text, maxWidth, scale, weight), x, y, color, scale, weight);
    }

    public String ellipsize(String text, int maxWidth, float scale, Weight weight) {
        if (text == null || textWidth(text, scale, weight) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int end = text.length();
        while (end > 0 && textWidth(text.substring(0, end) + ellipsis, scale, weight) > maxWidth) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    public void textCentered(String text, float centerX, float y, int color, float scale, Weight weight) {
        text(text, centerX - textWidth(text, scale, weight) / 2f, y, color, scale, weight);
    }

    /** Word-wrapped paragraph; returns the height used. */
    public int paragraph(String text, int x, int y, int width, int color, float scale) {
        int lineHeight = lineHeight(scale) + 1;
        int used = 0;
        for (String rawLine : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : rawLine.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (textWidth(candidate, scale, Weight.REGULAR) > width && !line.isEmpty()) {
                    text(line.toString(), x, y + used, color, scale, Weight.REGULAR);
                    used += lineHeight;
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            text(line.toString(), x, y + used, color, scale, Weight.REGULAR);
            used += lineHeight;
        }
        return used;
    }

    /** Height a paragraph would take. */
    public int paragraphHeight(String text, int width, float scale) {
        int lineHeight = lineHeight(scale) + 1;
        int lines = 0;
        for (String rawLine : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            lines++;
            for (String word : rawLine.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (textWidth(candidate, scale, Weight.REGULAR) > width && !line.isEmpty()) {
                    lines++;
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
        }
        return lines * lineHeight;
    }

    // ---- images -----------------------------------------------------------------------

    /** Draws a GPU texture into a rectangle with explicit texture coordinates. */
    public void texture(GpuTextureView view, GpuSampler sampler, int x0, int y0, int x1, int y1,
                        float u0, float u1, float v0, float v1) {
        graphics.blit(view, sampler, x0, y0, x1, y1, u0, u1, v0, v1);
    }

    // ---- clipping / layering ----------------------------------------------------------

    public void pushClip(int x, int y, int w, int h) {
        graphics.enableScissor(x, y, x + w, y + h);
    }

    public void popClip() {
        graphics.disableScissor();
    }

    /** Everything drawn after this sits above everything drawn before (popups, tooltips). */
    public void raise() {
        graphics.nextStratum();
    }

    public boolean hovered(int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
                && graphics.containsPointInScissor(mouseX, mouseY);
    }
}
