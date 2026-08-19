package dev.streamable.compositor;

import dev.streamable.source.transform.Point2;

/**
 * The coordinate system every source transform is expressed in.
 *
 * <p>Four different pixel spaces exist in this mod and confusing them is the
 * classic source of "my overlay moved when I resized the window" bugs:</p>
 *
 * <ol>
 *   <li><b>GUI-scaled coordinates</b> - what Minecraft screens draw in; changes
 *       with the GUI Scale option.</li>
 *   <li><b>Framebuffer pixels</b> - the real window size; changes with resize,
 *       fullscreen and HiDPI.</li>
 *   <li><b>Program canvas</b> - the fixed logical composition surface, defined
 *       by this class. Source transforms live here and <em>nowhere else</em>.</li>
 *   <li><b>Output resolution</b> - what the encoder receives; may differ from
 *       the canvas (e.g. a 3440x1440 canvas downscaled to 1920x1080).</li>
 * </ol>
 *
 * <p>Because transforms are stored in canvas space, an overlay keeps its exact
 * position when the player changes GUI scale, resizes the window or toggles
 * fullscreen. Conversions to and from screen space go through
 * {@link #mappingTo(int, int)} rather than ad-hoc multipliers scattered
 * through UI code.</p>
 *
 * @param width  canvas width in pixels
 * @param height canvas height in pixels
 */
public record ProgramCanvas(int width, int height) {

    public static final ProgramCanvas DEFAULT = new ProgramCanvas(1920, 1080);

    public ProgramCanvas {
        width = Math.clamp(width, 16, 16384);
        height = Math.clamp(height, 16, 16384);
    }

    public double aspectRatio() {
        return width / (double) height;
    }

    public Point2 center() {
        return new Point2(width / 2.0, height / 2.0);
    }

    /**
     * Builds the letterboxed mapping from this canvas onto a target rectangle.
     *
     * <p>Aspect ratio is always preserved: if the target is a different shape
     * the canvas is centred and bars are left over. That keeps overlays
     * un-distorted between the player's ultrawide monitor and a 16:9 stream.</p>
     */
    public Mapping mappingTo(int targetWidth, int targetHeight) {
        if (targetWidth <= 0 || targetHeight <= 0) {
            return new Mapping(this, 1.0, 0, 0);
        }
        double scale = Math.min(targetWidth / (double) width, targetHeight / (double) height);
        double offsetX = (targetWidth - width * scale) / 2.0;
        double offsetY = (targetHeight - height * scale) / 2.0;
        return new Mapping(this, scale, offsetX, offsetY);
    }

    /**
     * A canvas-to-target transform: uniform scale plus centring offset.
     *
     * @param canvas  the source canvas
     * @param scale   canvas pixels to target pixels
     * @param offsetX left letterbox bar width, in target pixels
     * @param offsetY top letterbox bar height, in target pixels
     */
    public record Mapping(ProgramCanvas canvas, double scale, double offsetX, double offsetY) {

        /** Canvas point to target (screen or output) point. */
        public Point2 toTarget(Point2 canvasPoint) {
            return new Point2(canvasPoint.x() * scale + offsetX, canvasPoint.y() * scale + offsetY);
        }

        /** Target point back to canvas point - used to route mouse input. */
        public Point2 toCanvas(Point2 targetPoint) {
            if (scale == 0) {
                return Point2.ZERO;
            }
            return new Point2((targetPoint.x() - offsetX) / scale, (targetPoint.y() - offsetY) / scale);
        }

        /** Scalar length conversion, for handle radii and border widths. */
        public double toTargetLength(double canvasLength) {
            return canvasLength * scale;
        }

        public double toCanvasLength(double targetLength) {
            return scale == 0 ? 0 : targetLength / scale;
        }
    }
}
