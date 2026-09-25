package dev.streamable.compositor;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatermarkRendererTest {

    @Test
    void rasterisesTextWithTransparentBackgroundAndOpaqueGlyphs() throws Exception {
        System.setProperty("java.awt.headless", "true");
        BufferedImage image = WatermarkRenderer.rasterise("twitch.tv/example", 36);
        assertTrue(image.getWidth() > image.getHeight() * 3, "a line of text is wide");
        assertEquals(BufferedImage.TYPE_INT_ARGB_PRE, image.getType());
        int transparent = 0;
        int solid = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha == 0) {
                    transparent++;
                } else if (alpha == 255) {
                    solid++;
                }
            }
        }
        assertTrue(transparent > 0, "background stays transparent");
        assertTrue(solid > 0, "glyphs are drawn");
        assertEquals(0xFFFFFFFF, brightestPixel(image), "white text");
    }

    private static int brightestPixel(BufferedImage image) {
        int best = 0;
        int bestSum = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int p = image.getRGB(x, y);
                int sum = ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF) + (p >>> 24);
                if (sum > bestSum) {
                    bestSum = sum;
                    best = p;
                }
            }
        }
        return best;
    }
}
