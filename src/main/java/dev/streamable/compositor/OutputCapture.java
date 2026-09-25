package dev.streamable.compositor;

import dev.streamable.StreamAbleLog;
import dev.streamable.pipeline.FrameBufferPool;
import dev.streamable.pipeline.PooledFrame;
import dev.streamable.video.OutputTransform;
import dev.streamable.video.Resolution;
import dev.streamable.video.ScalingMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;

/**
 * GPU scaling and asynchronous readback for one output (recording or stream).
 *
 * <pre>
 *   program canvas texture (e.g. 3440x1440)
 *        |  one textured quad, geometry from OutputTransform (Fit/Fill/Crop/...)
 *        v
 *   output FBO (e.g. 1920x1080), rows stored top-first
 *        |  glReadPixels into a PBO + fence (never waits)
 *        v
 *   PooledFrame (top-down RGB24) -> encoder queue
 * </pre>
 *
 * <p>Scaling happens on the GPU <em>before</em> readback, so a 1080p stream of
 * a 5120x1440 canvas reads back 6 MB per frame rather than 22 MB, and FFmpeg
 * never has to scale. Recording and streaming each own one of these, so they
 * can run at different resolutions from one composed program frame.</p>
 *
 * <h2>Readback</h2>
 * <p>Three pixel-buffer objects rotate. A capture renders the output, starts
 * an asynchronous {@code glReadPixels} and inserts a fence; later frames poll
 * the fence with a zero timeout and only map the buffer once the GPU is done.
 * If all three are still in flight the capture is skipped and its frame count
 * carried into the next one, so the timeline is never shortened.</p>
 *
 * <p>All methods run on the render thread.</p>
 */
public final class OutputCapture implements AutoCloseable {

    public static final int BYTES_PER_PIXEL = 3;
    private static final int RING = 3;

    /** Receives a completed frame and how many consecutive output frames it covers. */
    @FunctionalInterface
    public interface Sink {
        void accept(PooledFrame frame, int repeat);
    }

    private final String name;
    private final WatermarkRenderer watermark = new WatermarkRenderer();
    private java.util.function.Supplier<dev.streamable.config.VideoSettings.Watermark> watermarkSource;
    private final Resolution output;
    private final int frameBytes;
    private final FrameBufferPool pool;

    private int framebuffer;
    private int colorTexture;
    private final int[] pbo = new int[RING];
    private final long[] fence = new long[RING];
    private final int[] pendingRepeat = new int[RING];
    private final long[] pendingCaptureNanos = new long[RING];
    private int writeIndex;
    private int carriedRepeat;
    private boolean broken;

    // ---- diagnostics (render thread writes, UI reads) ----
    private volatile double lastReadbackMillis;
    private volatile double averageReadbackMillis = -1;
    private volatile long captures;
    private volatile long skippedCaptures;
    private volatile long poolExhausted;
    private volatile OutputTransform lastTransform;

    /** Draws the watermark this supplier returns (read every frame, so edits apply live); null for none. */
    public void setWatermark(java.util.function.Supplier<dev.streamable.config.VideoSettings.Watermark> source) {
        this.watermarkSource = source;
    }

    public OutputCapture(String name, Resolution output, int maxBuffers) {
        this.name = name;
        this.output = output;
        this.frameBytes = output.frameBytesInt(BYTES_PER_PIXEL);
        this.pool = new FrameBufferPool(frameBytes, maxBuffers);
    }

    public Resolution output() {
        return output;
    }

    public String name() {
        return name;
    }

    public boolean isBroken() {
        return broken;
    }

    private boolean ensureTargets() {
        if (broken) {
            return false;
        }
        if (framebuffer != 0) {
            return true;
        }
        int prevFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            colorTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, output.width(), output.height(), 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, colorTexture, 0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete output framebuffer: 0x" + Integer.toHexString(status));
            }
            for (int i = 0; i < RING; i++) {
                pbo[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, frameBytes, GL15.GL_STREAM_READ);
            }
            StreamAbleLog.COMPOSITOR.info("{} output ready at {} ({} MB per frame)", name, output.label(),
                    String.format(java.util.Locale.ROOT, "%.1f", frameBytes / 1_048_576.0));
            return true;
        } catch (RuntimeException e) {
            StreamAbleLog.COMPOSITOR.error("Could not create the {} output target", name, e);
            broken = true;
            release();
            return false;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPbo);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFramebuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);
        }
    }

    /**
     * Renders the program into this output and starts an asynchronous readback.
     *
     * @param programTexture  the composed canvas texture (bottom-up GL storage)
     * @param canvas          its size
     * @param mode            how the canvas maps onto this output
     * @param repeat          how many output frames this capture stands for
     */
    public void capture(GlQuadRenderer renderer, int programTexture, Resolution canvas, ScalingMode mode, int repeat) {
        if (repeat <= 0 || !ensureTargets()) {
            return;
        }
        int slot = writeIndex;
        if (fence[slot] != 0) {
            // The ring is full: the GPU has not finished three captures yet.
            // Carry this capture's frames forward rather than losing them.
            carriedRepeat += repeat;
            skippedCaptures++;
            return;
        }
        OutputTransform transform = OutputTransform.compute(canvas, output, mode);
        lastTransform = transform;

        int prevFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        // Other mods leave pack state behind (Voxy's model bakery sets
        // GL_PACK_ROW_LENGTH to its texture width and keeps it), which packs
        // every row of the frame into the first few rows of the buffer.
        int prevAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int prevRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int prevSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int prevSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int[] prevViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
        float[] clear = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clear);
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL11.glViewport(0, 0, output.width(), output.height());
            if (transform.hasBars()) {
                GL11.glClearColor(0f, 0f, 0f, 1f);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            }
            // Canvas y grows downwards but GL stores it bottom-up, so v is inverted.
            float[] uv = transform.sourceUv();
            renderer.drawTextureRegion(programTexture, transform.dstX(), transform.dstY(),
                    transform.dstW(), transform.dstH(), uv[0], 1f - uv[1], uv[2], 1f - uv[3],
                    output.width(), output.height(), true, false);
            var mark = watermarkSource == null ? null : watermarkSource.get();
            if (mark != null) {
                watermark.draw(renderer, mark, output);
            }

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[slot]);
            GL11.glReadPixels(0, 0, output.width(), output.height(), GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, 0L);
            fence[slot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            pendingRepeat[slot] = repeat + carriedRepeat;
            pendingCaptureNanos[slot] = System.nanoTime();
            carriedRepeat = 0;
            writeIndex = (slot + 1) % RING;
            captures++;
        } catch (RuntimeException e) {
            StreamAbleLog.COMPOSITOR.error("{} output capture failed; disabling it", name, e);
            broken = true;
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevAlignment);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, prevRowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, prevSkipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, prevSkipPixels);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPbo);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFramebuffer);
            GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            GL11.glClearColor(clear[0], clear[1], clear[2], clear[3]);
        }
    }

    /**
     * Delivers every readback the GPU has finished, oldest first. Call once per
     * rendered frame, whether or not a capture was due.
     */
    public void collect(Sink sink) {
        if (broken || framebuffer == 0) {
            return;
        }
        int prevPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            for (int n = 0; n < RING; n++) {
                int slot = (writeIndex + n) % RING;   // oldest pending first
                long sync = fence[slot];
                if (sync == 0) {
                    continue;
                }
                int status = GL32.glClientWaitSync(sync, 0, 0L);
                if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) {
                    break;   // keep delivery in order
                }
                GL32.glDeleteSync(sync);
                fence[slot] = 0;
                deliver(slot, sink);
            }
        } catch (RuntimeException e) {
            StreamAbleLog.COMPOSITOR.error("{} output readback failed; disabling it", name, e);
            broken = true;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPbo);
        }
    }

    private void deliver(int slot, Sink sink) {
        int repeat = pendingRepeat[slot];
        PooledFrame frame = pool.acquire(pendingCaptureNanos[slot]);
        if (frame == null) {
            // Every buffer is still queued for the encoder: keep the count, lose the picture.
            poolExhausted++;
            carriedRepeat += repeat;
            return;
        }
        long start = System.nanoTime();
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[slot]);
        ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, frameBytes, GL30.GL_MAP_READ_BIT);
        if (mapped == null) {
            frame.release();
            carriedRepeat += repeat;
            return;
        }
        try {
            mapped.get(0, frame.data(), 0, frameBytes);
        } finally {
            GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
        }
        double millis = (System.nanoTime() - start) / 1e6;
        lastReadbackMillis = millis;
        averageReadbackMillis = averageReadbackMillis < 0 ? millis : averageReadbackMillis * 0.9 + millis * 0.1;
        try {
            sink.accept(frame, repeat);
        } finally {
            frame.release();
        }
    }

    // ---- diagnostics ------------------------------------------------------------

    /** Time spent copying a finished readback out of GPU-visible memory. */
    public double averageReadbackMillis() {
        return averageReadbackMillis;
    }

    public long captures() {
        return captures;
    }

    /** Captures skipped because three readbacks were still in flight. */
    public long skippedCaptures() {
        return skippedCaptures;
    }

    /** Pictures lost because every frame buffer was still waiting for the encoder. */
    public long poolExhausted() {
        return poolExhausted;
    }

    public long memoryBytes() {
        return pool.retainedBytes() + (long) RING * frameBytes;
    }

    public OutputTransform lastTransform() {
        return lastTransform;
    }

    private void release() {
        for (int i = 0; i < RING; i++) {
            if (fence[i] != 0) {
                GL32.glDeleteSync(fence[i]);
                fence[i] = 0;
            }
            if (pbo[i] != 0) {
                GL15.glDeleteBuffers(pbo[i]);
                pbo[i] = 0;
            }
        }
        if (framebuffer != 0) {
            GL30.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
        if (colorTexture != 0) {
            GL11.glDeleteTextures(colorTexture);
            colorTexture = 0;
        }
    }

    /** Releases GL objects. Render thread only. */
    @Override
    public void close() {
        release();
        watermark.close();
    }
}
