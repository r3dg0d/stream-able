package dev.streamable.compositor;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import dev.streamable.StreamAbleLog;
import dev.streamable.video.Resolution;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * An off-screen framebuffer at the program canvas size.
 *
 * <p>The colour texture is allocated through Minecraft's {@link GpuDevice}
 * when possible, which gives it a {@link GpuTextureView} the Studio can draw
 * as a live preview inside the normal GUI pipeline (correct layering with
 * tooltips and popups). If that fails for any reason a plain GL texture is
 * used and only the preview is lost.</p>
 */
final class ProgramTarget implements AutoCloseable {

    private final Resolution size;
    private int framebuffer;
    private int textureId;
    private GpuTexture gpuTexture;
    private GpuTextureView view;

    private ProgramTarget(Resolution size) {
        this.size = size;
    }

    static ProgramTarget create(Resolution size, String label) {
        ProgramTarget target = new ProgramTarget(size);
        target.allocate(label);
        return target;
    }

    private void allocate(String label) {
        int prevFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            try {
                GpuDevice device = RenderSystem.getDevice();
                gpuTexture = device.createTexture(label,
                        GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                        TextureFormat.RGBA8, size.width(), size.height(), 1, 1);
                if (gpuTexture instanceof GlTexture gl) {
                    textureId = gl.glId();
                    view = device.createTextureView(gpuTexture);
                } else {
                    gpuTexture.close();
                    gpuTexture = null;
                }
            } catch (RuntimeException e) {
                StreamAbleLog.COMPOSITOR.debug("Device texture unavailable; falling back to raw GL: {}", e.toString());
                gpuTexture = null;
                view = null;
            }
            if (textureId == 0) {
                textureId = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, size.width(), size.height(), 0,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, textureId, 0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete program framebuffer: 0x" + Integer.toHexString(status));
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFramebuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);
        }
    }

    Resolution size() {
        return size;
    }

    int framebuffer() {
        return framebuffer;
    }

    int textureId() {
        return textureId;
    }

    /** For GUI preview; {@code null} when the device path was unavailable. */
    GpuTextureView view() {
        return view;
    }

    @Override
    public void close() {
        if (framebuffer != 0) {
            GL30.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
        if (view != null) {
            view.close();
            view = null;
        }
        if (gpuTexture != null) {
            gpuTexture.close();
            gpuTexture = null;
        } else if (textureId != 0) {
            GL11.glDeleteTextures(textureId);
        }
        textureId = 0;
    }
}
