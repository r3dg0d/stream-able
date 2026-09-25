package dev.streamable.config;

import dev.streamable.video.Resolution;
import dev.streamable.video.ScalingMode;

/**
 * The four independent video resolutions and how they relate.
 *
 * <pre>
 *   Minecraft framebuffer (whatever the window is)
 *        | gameScaling
 *   Program canvas (canvasWidth x canvasHeight) - source transforms live here
 *        | recording.mode               | streaming.mode
 *   Recording output                    Streaming output
 * </pre>
 *
 * <p>The preview is a fourth size, derived from the UI layout, and never
 * affects any output.</p>
 */
public final class VideoSettings {

    /** One output's size and mapping from the canvas. */
    public static final class Output {
        /** When true the output always equals the canvas (rounded down to even) and {@link #mode} is NATIVE. */
        public boolean matchCanvas = false;
        public int width = 1920;
        public int height = 1080;
        public ScalingMode mode = ScalingMode.FIT;

        public Output() {
        }

        Output(boolean matchCanvas, int width, int height, ScalingMode mode) {
            this.matchCanvas = matchCanvas;
            this.width = width;
            this.height = height;
            this.mode = mode;
        }

        /**
         * The output resolution for a canvas. A canvas-matched output is
         * rounded down to even dimensions (encoders need even sizes); that is
         * the one derived adjustment Stream-able makes, and the Video page
         * shows it. User-entered sizes are returned exactly as typed.
         */
        public Resolution resolve(Resolution canvas) {
            if (matchCanvas) {
                return canvas.nearestEven();
            }
            Resolution explicit = Resolution.tryOf(width, height);
            return explicit != null ? explicit : canvas.nearestEven();
        }

        public ScalingMode effectiveMode() {
            return matchCanvas ? ScalingMode.NATIVE : mode;
        }

        void validate() {
            if (mode == null) {
                mode = ScalingMode.FIT;
            }
            width = Math.clamp(width, Resolution.MIN_DIMENSION, Resolution.MAX_DIMENSION);
            height = Math.clamp(height, Resolution.MIN_DIMENSION, Resolution.MAX_DIMENSION);
        }
    }

    /** Canvas size. {@link #canvasFollowsGame} new installs adopt the window size on first frame. */
    public int canvasWidth = 1920;
    public int canvasHeight = 1080;
    /** Set once the canvas has been sized, so it never silently changes afterwards. */
    public boolean canvasInitialised = false;
    /** How the Minecraft frame is mapped onto the canvas when their sizes differ. */
    public ScalingMode gameScaling = ScalingMode.FIT;

    /** A text watermark drawn into outputs, in a corner of each output frame. */
    public static final class Watermark {
        public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

        public boolean enabled = false;
        public String text = "";
        public Corner corner = Corner.BOTTOM_RIGHT;
        /** Text height as a percentage of the output height. */
        public double sizePercent = 3.0;
        public double opacity = 0.8;
        public boolean onRecording = true;
        public boolean onStream = true;

        public boolean shows() {
            return enabled && text != null && !text.isBlank();
        }

        void validate() {
            if (corner == null) {
                corner = Corner.BOTTOM_RIGHT;
            }
            if (text == null) {
                text = "";
            }
            text = text.length() > 120 ? text.substring(0, 120) : text;
            sizePercent = Double.isFinite(sizePercent) ? Math.clamp(sizePercent, 1.0, 15.0) : 3.0;
            opacity = Double.isFinite(opacity) ? Math.clamp(opacity, 0.05, 1.0) : 0.8;
        }
    }

    public Watermark watermark = new Watermark();

    public Output recording = new Output(true, 1920, 1080, ScalingMode.NATIVE);
    public Output streaming = new Output(false, 1920, 1080, ScalingMode.FIT);

    /** Draws the streaming output's visible region over the preview when it crops or bars. */
    public boolean showSafeAreaGuides = true;
    /**
     * While a Stream-able screen is open, outputs keep showing the last game
     * frame from before it opened, so the Studio (with stream settings on it)
     * never reaches viewers or the recording.
     */
    public boolean hideStudioFromOutputs = true;

    public Resolution canvas() {
        Resolution canvas = Resolution.tryOf(canvasWidth, canvasHeight);
        return canvas != null ? canvas : Resolution.FULL_HD;
    }

    public void setCanvas(Resolution canvas) {
        canvasWidth = canvas.width();
        canvasHeight = canvas.height();
        canvasInitialised = true;
    }

    public void validate() {
        if (recording == null) {
            recording = new Output(true, 1920, 1080, ScalingMode.NATIVE);
        }
        if (streaming == null) {
            streaming = new Output(false, 1920, 1080, ScalingMode.FIT);
        }
        if (gameScaling == null) {
            gameScaling = ScalingMode.FIT;
        }
        canvasWidth = Math.clamp(canvasWidth, Resolution.MIN_DIMENSION, Resolution.MAX_DIMENSION);
        canvasHeight = Math.clamp(canvasHeight, Resolution.MIN_DIMENSION, Resolution.MAX_DIMENSION);
        if ((long) canvasWidth * canvasHeight > Resolution.MAX_PIXELS) {
            canvasWidth = 3840;
            canvasHeight = 2160;
        }
        recording.validate();
        streaming.validate();
        if (watermark == null) {
            watermark = new Watermark();
        }
        watermark.validate();
    }
}
