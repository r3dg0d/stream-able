package dev.streamable.compositor;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.streamable.StreamAbleLog;
import dev.streamable.browser.BrowserHandle;
import dev.streamable.browser.BrowserSourceManager;
import dev.streamable.source.BrowserSource;
import dev.streamable.source.SourceList;
import dev.streamable.source.transform.SourceTransform;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Predicate;

/**
 * Builds the final program frame that recordings and streams actually receive.
 *
 * <p>The naive approach - capture Minecraft's framebuffer, then draw browser
 * sources on the local screen afterwards - produces overlays that the streamer
 * can see but viewers cannot. This class avoids that by compositing into its
 * own off-screen framebuffer:</p>
 *
 * <pre>
 *   Minecraft main render target
 *            |
 *            v
 *   [ program framebuffer ]  &lt;- browser sources drawn here, in z-order
 *            |
 *            +--&gt; PBO readback --&gt; recorder / streamer
 * </pre>
 *
 * <p>Because the output frame is assembled separately from the visible screen,
 * a source can appear on stream but not locally (or the reverse) simply by
 * changing its {@link dev.streamable.source.OutputRouting}.</p>
 *
 * <p>Readback uses two pixel-buffer objects in a ping-pong pattern so
 * {@code glReadPixels} never stalls the render thread waiting for the GPU; the
 * cost is that the returned frame is one frame behind, which is irrelevant for
 * recording and streaming.</p>
 */
public final class ProgramCompositor implements AutoCloseable {

    private static final int BYTES_PER_PIXEL = 3;

    private final GlQuadRenderer quadRenderer = new GlQuadRenderer();
    private final int[] pboIds = new int[2];

    private ProgramCanvas canvas = ProgramCanvas.DEFAULT;
    private int framebufferId;
    private int colorTextureId;
    private int pboWriteIndex;
    private boolean hasPendingFrame;
    private boolean pboSupported = true;
    private boolean broken;

    /** Chromium delivers premultiplied alpha; overridable if a CEF build differs. */
    private boolean premultipliedBrowserAlpha = true;

    public ProgramCanvas canvas() {
        return canvas;
    }

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

    /**
     * Ensures the framebuffer exists at the requested canvas size.
     *
     * @return {@code true} when the compositor is ready to draw
     */
    public boolean ensureTarget(ProgramCanvas requested) {
        if (broken) {
            return false;
        }
        if (!quadRenderer.initialise()) {
            broken = true;
            return false;
        }
        if (framebufferId != 0 && requested.equals(canvas)) {
            return true;
        }
        releaseTarget();
        canvas = requested;

        int prevFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            colorTextureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTextureId);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, canvas.width(), canvas.height(),
                    0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            framebufferId = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, colorTextureId, 0);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete framebuffer: 0x" + Integer.toHexString(status));
            }
            StreamAbleLog.COMPOSITOR.info("Program canvas ready at {}x{}", canvas.width(), canvas.height());
            return true;
        } catch (RuntimeException e) {
            StreamAbleLog.COMPOSITOR.error("Could not create the program framebuffer", e);
            broken = true;
            releaseTarget();
            return false;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFramebuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);
        }
    }

    /**
     * Renders the game plus the selected sources into the program framebuffer.
     *
     * @param sources  all configured sources, in z-order
     * @param browsers live browsers keyed by source id
     * @param include  routing filter, e.g. {@code s -> s.routing().includeInStream()}
     */
    public void composeProgramFrame(SourceList sources, BrowserSourceManager browsers,
                                    Predicate<BrowserSource> include) {
        if (broken || framebufferId == 0) {
            return;
        }
        int prevFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] prevViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
            GL11.glViewport(0, 0, canvas.width(), canvas.height());
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            drawGameFrame();
            drawSources(sources, browsers, include, canvas.width(), canvas.height(), null);
        } catch (RuntimeException e) {
            StreamAbleLog.COMPOSITOR.error("Failed to compose the program frame", e);
            broken = true;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFramebuffer);
            GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        }
    }

    /** Draws Minecraft's finished frame, scaled to fill the canvas. */
    private void drawGameFrame() {
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        if (target == null) {
            return;
        }
        GpuTexture texture = target.getColorTexture();
        if (!(texture instanceof GlTexture gl)) {
            return;
        }
        // Minecraft's render target uses the OpenGL bottom-left origin, so it is
        // sampled flipped to appear upright on our top-left-origin canvas.
        quadRenderer.draw(gl.glId(), SourceTransform.of(0, 0, canvas.width(), canvas.height()),
                canvas.width(), canvas.height(), 1.0f, true, false);
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
     * display are skipped here but still reach
     * {@link #composeProgramFrame}.</p>
     */
    public void renderToScreen(SourceList sources, BrowserSourceManager browsers,
                               int screenWidth, int screenHeight) {
        if (broken || !quadRenderer.isUsable()) {
            return;
        }
        ProgramCanvas.Mapping mapping = canvas.mappingTo(screenWidth, screenHeight);
        drawSources(sources, browsers, s -> s.routing().showLocally(),
                screenWidth, screenHeight, mapping);
    }

    /** The mapping from canvas space onto the given screen, for input routing. */
    public ProgramCanvas.Mapping screenMapping(int screenWidth, int screenHeight) {
        return canvas.mappingTo(screenWidth, screenHeight);
    }

    /**
     * Reads the composed frame back as top-down RGB bytes.
     *
     * @return pixels ready for FFmpeg, or {@code null} while the async readback
     * is still warming up (the first call after start or resize)
     */
    public byte[] readFrame() {
        if (broken || framebufferId == 0) {
            return null;
        }
        int width = canvas.width();
        int height = canvas.height();
        int byteSize = width * height * BYTES_PER_PIXEL;

        int prevFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int prevAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebufferId);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);

            if (!pboSupported) {
                return readSynchronously(width, height, byteSize);
            }
            if (pboIds[0] == 0) {
                initialisePbos(byteSize);
            }
            byte[] ready = hasPendingFrame ? mapPendingFrame(width, height, byteSize) : null;
            submitAsyncRead(width, height, byteSize);
            return ready;
        } catch (RuntimeException e) {
            StreamAbleLog.COMPOSITOR.warn("PBO readback failed; falling back to synchronous read", e);
            pboSupported = false;
            deletePbos();
            try {
                return readSynchronously(width, height, byteSize);
            } catch (RuntimeException fallback) {
                StreamAbleLog.COMPOSITOR.error("Frame readback failed entirely", fallback);
                return null;
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevAlignment);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevFramebuffer);
        }
    }

    private void initialisePbos(int byteSize) {
        for (int i = 0; i < pboIds.length; i++) {
            pboIds[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, byteSize, GL15.GL_STREAM_READ);
        }
        pboWriteIndex = 0;
        hasPendingFrame = false;
    }

    private void submitAsyncRead(int width, int height, int byteSize) {
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[pboWriteIndex]);
        GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, byteSize, GL15.GL_STREAM_READ);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, 0L);
        pboWriteIndex = (pboWriteIndex + 1) % pboIds.length;
        hasPendingFrame = true;
    }

    private byte[] mapPendingFrame(int width, int height, int byteSize) {
        int readIndex = (pboWriteIndex + 1) % pboIds.length;
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[readIndex]);
        ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, byteSize, null);
        if (mapped == null) {
            return null;
        }
        try {
            return flipVertically(mapped, width, height);
        } finally {
            GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
        }
    }

    private byte[] readSynchronously(int width, int height, int byteSize) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(byteSize);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buffer);
        return flipVertically(buffer, width, height);
    }

    /**
     * OpenGL returns rows bottom-up; FFmpeg's rawvideo expects top-down, so the
     * rows are reversed on the way out. Copying whole rows keeps this cheap.
     */
    private static byte[] flipVertically(ByteBuffer source, int width, int height) {
        int stride = width * BYTES_PER_PIXEL;
        byte[] out = new byte[stride * height];
        for (int row = 0; row < height; row++) {
            source.position((height - 1 - row) * stride);
            source.get(out, row * stride, stride);
        }
        source.position(0);
        return out;
    }

    private void deletePbos() {
        for (int i = 0; i < pboIds.length; i++) {
            if (pboIds[i] != 0) {
                GL15.glDeleteBuffers(pboIds[i]);
                pboIds[i] = 0;
            }
        }
        hasPendingFrame = false;
    }

    private void releaseTarget() {
        if (framebufferId != 0) {
            GL30.glDeleteFramebuffers(framebufferId);
            framebufferId = 0;
        }
        if (colorTextureId != 0) {
            GL11.glDeleteTextures(colorTextureId);
            colorTextureId = 0;
        }
        deletePbos();
    }

    @Override
    public void close() {
        releaseTarget();
        quadRenderer.close();
    }
}
