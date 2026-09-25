package dev.streamable.compositor;

import dev.streamable.StreamAbleLog;
import dev.streamable.config.VideoSettings;
import dev.streamable.video.Resolution;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

import java.awt.AlphaComposite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Draws the text watermark into one output's frame, after scaling, so it sits
 * in the same corner of every output regardless of how that output crops the
 * canvas. Render thread only; one instance per output.
 *
 * <p>The text is rasterised once with Java2D in the bundled Inter typeface
 * (with a soft shadow for legibility on bright scenes) into a premultiplied
 * texture, and re-rasterised only when the text or pixel size changes.</p>
 */
final class WatermarkRenderer implements AutoCloseable {

    private static volatile Font baseFont;

    private int texture;
    private int textureWidth;
    private int textureHeight;
    private String cachedText;
    private int cachedPixels;

    void draw(GlQuadRenderer renderer, VideoSettings.Watermark watermark, Resolution output) {
        if (watermark == null || !watermark.shows()) {
            return;
        }
        int pixels = Math.max(8, (int) Math.round(output.height() * watermark.sizePercent / 100.0));
        String text = watermark.text.strip();
        if (!ensureTexture(text, pixels)) {
            return;
        }
        int margin = Math.max(4, Math.round(output.height() * 0.025f));
        double x = switch (watermark.corner) {
            case TOP_LEFT, BOTTOM_LEFT -> margin;
            case TOP_RIGHT, BOTTOM_RIGHT -> output.width() - margin - textureWidth;
        };
        double y = switch (watermark.corner) {
            case TOP_LEFT, TOP_RIGHT -> margin;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> output.height() - margin - textureHeight;
        };
        // The image is uploaded top row first, so v runs downwards like the output.
        renderer.drawTextureRegion(texture, x, y, textureWidth, textureHeight, 0f, 0f, 1f, 1f,
                output.width(), output.height(), true, true, (float) watermark.opacity);
    }

    private boolean ensureTexture(String text, int pixels) {
        if (texture != 0 && text.equals(cachedText) && pixels == cachedPixels) {
            return true;
        }
        BufferedImage image;
        try {
            image = rasterise(text, pixels);
        } catch (RuntimeException | java.awt.FontFormatException | java.io.IOException e) {
            StreamAbleLog.COMPOSITOR.warn("Could not draw the watermark text: {}", e.toString());
            cachedText = text;
            cachedPixels = pixels;
            return false;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int[] argb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();   // premultiplied
        ByteBuffer rgba = MemoryUtil.memAlloc(w * h * 4);
        try {
            for (int p : argb) {
                rgba.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
            }
            rgba.flip();
            if (texture == 0) {
                texture = GL11.glGenTextures();
            }
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int previousAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, previousAlignment);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        } finally {
            MemoryUtil.memFree(rgba);
        }
        textureWidth = w;
        textureHeight = h;
        cachedText = text;
        cachedPixels = pixels;
        return true;
    }

    /** The text as a premultiplied ARGB image, with a soft drop shadow. */
    static BufferedImage rasterise(String text, int pixels) throws java.awt.FontFormatException, java.io.IOException {
        Font font = font().deriveFont(Font.BOLD, (float) pixels);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g0 = probe.createGraphics();
        g0.setFont(font);
        FontMetrics metrics = g0.getFontMetrics();
        int shadow = Math.max(1, pixels / 12);
        int width = Math.max(1, metrics.stringWidth(text) + shadow * 2 + 2);
        int height = Math.max(1, metrics.getAscent() + metrics.getDescent() + shadow * 2);
        g0.dispose();

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setComposite(AlphaComposite.SrcOver);
        g.setFont(font);
        int baseline = metrics.getAscent();
        g.setColor(new java.awt.Color(0, 0, 0, 150));
        g.drawString(text, shadow + 1, baseline + shadow);
        g.setColor(java.awt.Color.WHITE);
        g.drawString(text, 1, baseline);
        g.dispose();
        return image;
    }

    private static Font font() throws java.awt.FontFormatException, java.io.IOException {
        Font font = baseFont;
        if (font == null) {
            try (InputStream in = WatermarkRenderer.class.getResourceAsStream(
                    "/assets/streamable/font/inter_semibold.ttf")) {
                font = in == null ? new Font(Font.SANS_SERIF, Font.BOLD, 12) : Font.createFont(Font.TRUETYPE_FONT, in);
            }
            baseFont = font;
        }
        return font;
    }

    @Override
    public void close() {
        if (texture != 0) {
            GL11.glDeleteTextures(texture);
            texture = 0;
        }
    }
}
