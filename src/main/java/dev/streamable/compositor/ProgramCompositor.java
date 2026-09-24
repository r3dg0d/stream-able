package dev.streamable.compositor;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.streamable.StreamAbleLog;
import dev.streamable.browser.BrowserHandle;
import dev.streamable.browser.BrowserSourceManager;
import dev.streamable.source.BrowserSource;
import dev.streamable.source.SourceList;
import dev.streamable.source.transform.SourceTransform;
import dev.streamable.video.OutputTransform;
import dev.streamable.video.Resolution;
import dev.streamable.video.ScalingMode;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.List;
import java.util.function.Predicate;

/**
 * Builds the program frames that recordings and streams actually receive.
 *
 * <pre>
 *   Minecraft main render target (any size, e.g. 3440x1440)
 *            |  game scaling (Fit / Fill / Native ...) via OutputTransform
 *            v
 *   [ program canvas FBO ]  &lt;- browser sources drawn here, in z-order,
 *            |                  in canvas coordinates
 *            +--&gt; OutputCapture (recording size) --&gt; recorder
 *            +--&gt; OutputCapture (stream size)    --&gt; encoder groups
 * </pre>
 *
 * <p>Because the output frame is assembled separately from the visible screen,
 * a source can appear on stream but not locally (or the reverse) simply by
 * changing its {@link dev.streamable.source.OutputRouting}. When recording and
 * streaming routings differ for some visible source, a second canvas is
 * composed for the stream; otherwise both outputs share one.</p>
 *
 * <h2>Keeping the Studio out of the broadcast</h2>
 * <p>The frame is captured at the end of the game's render pass, which includes
 * the HUD and any open screen - that is what viewers expect for the HUD. For
 * Stream-able's own screens (the Studio shows stream settings) the compositor
 * can instead keep using the last game frame captured before the screen
 * opened, while browser sources keep updating.</p>
 */
public final class ProgramCompositor implements AutoCloseable {

    private final GlQuadRenderer quadRenderer = new GlQuadRenderer();

    private ProgramTarget program;
    private ProgramTarget streamProgram;
    private ProgramTarget gameSnapshot;
    private boolean snapshotValid;
    private boolean broken;

    /** Chromium delivers premultiplied alpha; overridable if a CEF build differs. */
    private boolean premultipliedBrowserAlpha = true;

    // ---- diagnostics ----
    private volatile double composeMillis = -1;

    /** The shared GL program, so the editor overlay draws through the same path. */
    public GlQuadRenderer quadRenderer() {
        return quadRenderer;
    }

    public void setPremultipliedBrowserAlpha(boolean premultiplied) {
        this.premultipliedBrowserAlpha = premultiplied;
    }

    public boolean isUsable() {
        return !broken;
    }

    public Resolution canvas() {
        return program == null ? null : program.size();
    }

    /** Average GPU-command time for one composition, in milliseconds (CPU side). */
    public double composeMillis() {
        return composeMillis;
    }

    /**
     * Ensures the canvas framebuffers exist at the requested size, releasing
     * stale ones after a canvas change.
     *
     * @return {@code true} when the compositor is ready to draw
     */
    public boolean ensureCanvas(Resolution canvas, boolean separateStreamCanvas) {
        if (broken) {
            return false;
        }
        if (!quadRenderer.initialise()) {
            broken = true;
            return false;
        }
        try {
            if (program == null || !program.size().equals(canvas)) {
                closeTargets();
                program = ProgramTarget.create(canvas, "Stream-able program canvas");
                StreamAbleLog.COMPOSITOR.info("Program canvas ready at {}", canvas.label());
            }
            if (separateStreamCanvas && streamProgram == null) {
                streamProgram = ProgramTarget.create(canvas, "Stream-able stream canvas");
            } else if (!separateStreamCanvas && streamProgram != null) {
                streamProgram.close();
                streamProgram = null;
            }
            return true;
        } catch (RuntimeException e) {
            StreamAbleLog.COMPOSITOR.error("Could not create the program canvas at {}", canvas.label(), e);
            broken = true;
            closeTargets();
            return false;
        }
    }

    /** Legacy entry point used by the local overlay path. */
    public boolean ensureTarget(ProgramCanvas requested) {
        return ensureCanvas(requested.resolution(), streamProgram != null);
    }

    /** The main render target's colour texture and size, or {@code null}. */
    private static GameTexture gameTexture() {
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        if (target == null) {
            return null;
        }
        GpuTexture texture = target.getColorTexture();
        if (!(texture instanceof GlTexture gl) || target.width <= 0 || target.height <= 0) {
            return null;
        }
        Resolution size = Resolution.tryOf(target.width, target.height);
        return size == null ? null : new GameTexture(gl.glId(), size);
    }

    private record GameTexture(int id, Resolution size) {
    }

    /**
     * Copies the current game frame aside so it can stand in while a
     * Stream-able screen is open. Cheap: one GPU blit.
     */
    public void snapshotGame() {
        GameTexture game = gameTexture();
        if (game == null || broken || !quadRenderer.initialise()) {
            return;
        }
        try {
            if (gameSnapshot == null || !gameSnapshot.size().equals(game.size())) {
                if (gameSnapshot != null) {
                    gameSnapshot.close();
                }
                gameSnapshot = ProgramTarget.create(game.size(), "Stream-able game snapshot");
            }
            int prevFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            int[] prevViewport = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
            try {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, gameSnapshot.framebuffer());
                GL11.glViewport(0, 0, game.size().width(), game.size().height());
                // Same orientation in and out: an exact 1:1 copy.
                quadRenderer.drawTextureRegion(game.id(), 0, 0, game.size().width(), game.size().height(),
                        0f, 1f, 1f, 0f, game.size().width(), game.size().height(), false, false);
            } finally {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFramebuffer);
                GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            }
            snapshotValid = true;
        } catch (RuntimeException e) {
            // The snapshot is a privacy nicety; losing it must not stop capture.
            StreamAbleLog.COMPOSITOR.debug("Game snapshot unavailable: {}", e.toString());
            snapshotValid = false;
        }
    }

    public boolean hasGameSnapshot() {
        return snapshotValid;
    }

    /**
     * Renders the game plus the selected sources into the program canvas.
     *
     * @param forStream   compose into the separate stream canvas (when routings differ)
     * @param gameScaling how the game frame maps onto the canvas
     * @param useSnapshot draw the frozen game snapshot instead of the live frame
     */
    public void composeProgramFrame(SourceList sources, BrowserSourceManager browsers,
                                    Predicate<BrowserSource> include, boolean forStream,
                                    ScalingMode gameScaling, boolean useSnapshot) {
        ProgramTarget target = forStream && streamProgram != null ? streamProgram : program;
        if (broken || target == null) {
            return;
        }
        long start = System.nanoTime();
        Resolution canvas = target.size();
        int prevFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] prevViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
        float[] clear = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clear);
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer());
            GL11.glViewport(0, 0, canvas.width(), canvas.height());
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            drawGameFrame(canvas, gameScaling, useSnapshot && snapshotValid);
            drawSources(sources, browsers, include, canvas.width(), canvas.height(), null);
        } catch (RuntimeException e) {
            StreamAbleLog.COMPOSITOR.error("Failed to compose the program frame", e);
            broken = true;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFramebuffer);
            GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            GL11.glClearColor(clear[0], clear[1], clear[2], clear[3]);
        }
        double millis = (System.nanoTime() - start) / 1e6;
        composeMillis = composeMillis < 0 ? millis : composeMillis * 0.9 + millis * 0.1;
    }

    /** Draws Minecraft's frame into the canvas according to the game scaling mode. */
    private void drawGameFrame(Resolution canvas, ScalingMode gameScaling, boolean useSnapshot) {
        int textureId;
        Resolution size;
        if (useSnapshot) {
            textureId = gameSnapshot.textureId();
            size = gameSnapshot.size();
        } else {
            GameTexture game = gameTexture();
            if (game == null) {
                return;
            }
            textureId = game.id();
            size = game.size();
        }
        OutputTransform transform = OutputTransform.compute(size, canvas, gameScaling);
        float[] uv = transform.sourceUv();
        // Both textures are stored bottom-up: canvas-top maps to v = 1.
        quadRenderer.drawTextureRegion(textureId, transform.dstX(), transform.dstY(), transform.dstW(),
                transform.dstH(), uv[0], 1f - uv[1], uv[2], 1f - uv[3], canvas.width(), canvas.height(),
                false, false);
    }

    /**
     * Draws browser sources in z-order.
     *
     * @param mapping optional canvas-to-target mapping; {@code null} means the
     *                target already is the canvas (the off-screen pass)
     */
    private void drawSources(SourceList sources, BrowserSourceManager browsers,
                             Predicate<BrowserSource> include,
                             int targetWidth, int targetHeight, ProgramCanvas.Mapping mapping) {
        List<BrowserSource> ordered = sources.snapshot();   // back-to-front
        for (BrowserSource source : ordered) {
            if (!source.visible() || !include.test(source)) {
                continue;
            }
            BrowserHandle handle = browsers.handleFor(source.id());
            if (handle == null || !handle.hasFrame()) {
                continue;
            }
            int textureId = handle.textureId();
            if (textureId == 0) {
                continue;
            }
            SourceTransform transform = mapping == null
                    ? source.transform()
                    : mapTransform(source.transform(), mapping);
            // CEF uploads its top-down buffer starting at row 0, so v=0 is the
            // top of the page - no flip needed, unlike the game texture.
            quadRenderer.draw(textureId, transform, targetWidth, targetHeight,
                    source.opacity(), false, premultipliedBrowserAlpha);
        }
    }

    /** Applies a canvas-to-screen mapping to a transform, preserving rotation. */
    static SourceTransform mapTransform(SourceTransform transform, ProgramCanvas.Mapping mapping) {
        return new SourceTransform(
                transform.x() * mapping.scale() + mapping.offsetX(),
                transform.y() * mapping.scale() + mapping.offsetY(),
                transform.width() * mapping.scale(),
                transform.height() * mapping.scale(),
                transform.rotation());
    }

    /**
     * Draws the locally visible sources straight onto the screen.
     *
     * <p>Called at the end of the frame with Minecraft's own framebuffer bound,
     * so this is what the player sees. Sources whose routing excludes local
     * display are skipped here but still reach the program canvas.</p>
     */
    public void renderToScreen(SourceList sources, BrowserSourceManager browsers, Resolution canvas,
                               int screenWidth, int screenHeight) {
        if (broken || !quadRenderer.initialise()) {
            return;
        }
        ProgramCanvas.Mapping mapping = new ProgramCanvas(canvas).mappingTo(screenWidth, screenHeight);
        drawSources(sources, browsers, s -> s.routing().showLocally(), screenWidth, screenHeight, mapping);
    }

    /** Texture of a composed canvas, for output capture. */
    public int programTexture(boolean forStream) {
        ProgramTarget target = forStream && streamProgram != null ? streamProgram : program;
        return target == null ? 0 : target.textureId();
    }

    /** The recording/program canvas as a GUI-drawable view, or {@code null}. */
    public GpuTextureView previewView(boolean forStream) {
        ProgramTarget target = forStream && streamProgram != null ? streamProgram : program;
        return target == null ? null : target.view();
    }

    private void closeTargets() {
        if (program != null) {
            program.close();
            program = null;
        }
        if (streamProgram != null) {
            streamProgram.close();
            streamProgram = null;
        }
    }

    @Override
    public void close() {
        closeTargets();
        if (gameSnapshot != null) {
            gameSnapshot.close();
            gameSnapshot = null;
        }
        quadRenderer.close();
    }
}
