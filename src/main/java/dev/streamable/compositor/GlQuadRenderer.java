package dev.streamable.compositor;

import dev.streamable.StreamAbleLog;
import dev.streamable.source.transform.Point2;
import dev.streamable.source.transform.SourceTransform;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

/**
 * Draws textured, rotated, alpha-blended quads with a self-contained GL program.
 *
 * <p>Stream-able composites with its own shader rather than going through
 * Minecraft's render pipeline for two reasons: the compositing happens into an
 * off-screen framebuffer that Minecraft knows nothing about, and Minecraft's GUI
 * renderer in 26.x is a deferred, state-collected system that is awkward to
 * inject arbitrary textured geometry into. Keeping our own tiny program makes
 * the pixel path explicit and identical for the screen pass and the output pass.</p>
 *
 * <h2>Alpha</h2>
 * <p>Chromium hands out BGRA with <b>premultiplied</b> alpha, so the default
 * blend function is {@code (ONE, ONE_MINUS_SRC_ALPHA)} and the opacity uniform
 * scales all four channels. Straight (non-premultiplied) alpha is available as a
 * fallback because this is the single most likely thing to differ between CEF
 * builds, and getting it wrong shows up as dark or bright fringing around
 * anti-aliased overlay edges rather than as an obvious failure.</p>
 *
 * <h2>State discipline</h2>
 * <p>Every GL object this class binds is queried first and restored afterwards.
 * Minecraft caches a lot of GL state, so leaving anything changed corrupts
 * later rendering in ways that are extremely hard to trace.</p>
 */
public final class GlQuadRenderer implements AutoCloseable {

    private static final String VERTEX_SHADER = """
            #version 150 core
            in vec2 aPos;
            in vec2 aUv;
            uniform vec2 uViewport;
            uniform int uFlipTarget;
            out vec2 vUv;
            void main() {
                // Pixel coordinates (origin top-left) to clip space. A flipped
                // target stores the top row first in memory, so glReadPixels
                // returns top-down rows and no CPU flip is needed.
                float y = aPos.y / uViewport.y * 2.0;
                vec2 ndc = vec2(aPos.x / uViewport.x * 2.0 - 1.0,
                                uFlipTarget == 1 ? y - 1.0 : 1.0 - y);
                gl_Position = vec4(ndc, 0.0, 1.0);
                vUv = aUv;
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 150 core
            in vec2 vUv;
            uniform sampler2D uTex;
            uniform float uOpacity;
            uniform int uPremultiplied;
            uniform int uUseTexture;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                vec4 texel;
                if (uUseTexture == 1) {
                    texel = texture(uTex, vUv);
                    if (uPremultiplied == 0) {
                        // Straight alpha: premultiply so one blend func serves both.
                        texel = vec4(texel.rgb * texel.a, texel.a);
                    }
                } else {
                    // Solid fill for the editor's transform box and handles.
                    texel = vec4(uColor.rgb * uColor.a, uColor.a);
                }
                fragColor = texel * uOpacity;
            }
            """;

    private int program;
    private int vao;
    private int vbo;
    private int uViewport;
    private int uFlipTarget;
    private int uOpacity;
    private int uPremultiplied;
    private int uUseTexture;
    private int uColor;
    private int uTex;
    private boolean initialised;
    private boolean failed;

    /** Compiles the program. Must be called on the render thread with a live context. */
    public boolean initialise() {
        if (initialised) {
            return true;
        }
        if (failed) {
            return false;
        }
        try {
            int vertex = compile(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragment = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glBindAttribLocation(program, 0, "aPos");
            GL20.glBindAttribLocation(program, 1, "aUv");
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("Link failed: " + GL20.glGetProgramInfoLog(program));
            }
            // Shaders are no longer needed once linked.
            GL20.glDetachShader(program, vertex);
            GL20.glDetachShader(program, fragment);
            GL20.glDeleteShader(vertex);
            GL20.glDeleteShader(fragment);

            uViewport = GL20.glGetUniformLocation(program, "uViewport");
            uFlipTarget = GL20.glGetUniformLocation(program, "uFlipTarget");
            uOpacity = GL20.glGetUniformLocation(program, "uOpacity");
            uPremultiplied = GL20.glGetUniformLocation(program, "uPremultiplied");
            uUseTexture = GL20.glGetUniformLocation(program, "uUseTexture");
            uColor = GL20.glGetUniformLocation(program, "uColor");
            uTex = GL20.glGetUniformLocation(program, "uTex");

            int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int previousVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            // 4 vertices x (x, y, u, v)
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 4 * 4 * Float.BYTES, GL15.GL_STREAM_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0L);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
            GL30.glBindVertexArray(previousVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousVbo);

            initialised = true;
            StreamAbleLog.COMPOSITOR.debug("Compositor GL program ready.");
            return true;
        } catch (RuntimeException e) {
            failed = true;
            StreamAbleLog.COMPOSITOR.error(
                    "Could not create the compositor GL program; browser sources will not be drawn", e);
            return false;
        }
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Shader compile failed: " + log);
        }
        return shader;
    }

    public boolean isUsable() {
        return initialised && !failed;
    }

    /**
     * Draws a texture into the currently bound framebuffer, positioned by a
     * source transform.
     *
     * @param textureId      GL texture name to sample
     * @param transform      placement in target-space pixels (already mapped)
     * @param targetWidth    width of the bound framebuffer
     * @param targetHeight   height of the bound framebuffer
     * @param opacity        0..1 multiplier
     * @param flipVertically true when the source texture's origin is bottom-left
     * @param premultiplied  true when the texture already carries premultiplied alpha
     */
    public void draw(int textureId, SourceTransform transform, int targetWidth, int targetHeight,
                     float opacity, boolean flipVertically, boolean premultiplied) {
        if (textureId == 0) {
            return;
        }
        drawQuad(textureId, transform, targetWidth, targetHeight, opacity, flipVertically,
                premultiplied, true, 0);
    }

    /**
     * Draws a solid colour quad - the editor's red bounding box, handles and
     * rotation knob.
     *
     * @param argb colour in {@code 0xAARRGGBB}
     */
    public void drawSolid(SourceTransform transform, int targetWidth, int targetHeight, int argb) {
        drawQuad(0, transform, targetWidth, targetHeight, 1.0f, false, true, false, argb);
    }

    /**
     * Draws a solid quad from four explicit corners.
     *
     * <p>Used for editor geometry - border segments and handles - which is
     * thinner than {@link SourceTransform#MIN_SIZE} and therefore cannot be
     * expressed as a transform.
     *
     * @param corners four points in target pixels, ordered TL, TR, BR, BL
     */
    public void drawSolidCorners(Point2[] corners, int targetWidth, int targetHeight, int argb) {
        drawQuad(0, corners, targetWidth, targetHeight, 1.0f, false, true, false, argb);
    }

    private void drawQuad(int textureId, SourceTransform transform, int targetWidth, int targetHeight,
                          float opacity, boolean flipVertically, boolean premultiplied,
                          boolean useTexture, int argb) {
        drawQuad(textureId, transform.corners(), targetWidth, targetHeight, opacity,
                flipVertically, premultiplied, useTexture, argb);
    }

    private void drawQuad(int textureId, Point2[] corners, int targetWidth, int targetHeight,
                          float opacity, boolean flipVertically, boolean premultiplied,
                          boolean useTexture, int argb) {
        float v0 = flipVertically ? 1.0f : 0.0f;
        float v1 = flipVertically ? 0.0f : 1.0f;
        drawQuad(textureId, corners, 0f, v0, 1f, v1, targetWidth, targetHeight, opacity,
                premultiplied, useTexture, argb, false, true);
    }

    /**
     * Draws a sub-rectangle of a texture onto an axis-aligned target rectangle.
     *
     * <p>Used by the output scaler: the UV rectangle comes straight from
     * {@link dev.streamable.video.OutputTransform}, so the GPU samples exactly
     * the region the geometry maths selected.</p>
     *
     * @param u0 texture u at the rectangle's left edge
     * @param v0 texture v at the rectangle's top edge
     * @param u1 texture u at the right edge
     * @param v1 texture v at the bottom edge
     * @param flipTarget store the top row first, for top-down readback
     * @param blend      false to overwrite the target (opaque copy)
     */
    public void drawTextureRegion(int textureId, double x, double y, double width, double height,
                                  float u0, float v0, float u1, float v1,
                                  int targetWidth, int targetHeight, boolean flipTarget, boolean blend) {
        if (textureId == 0) {
            return;
        }
        Point2[] corners = {
                new Point2(x, y), new Point2(x + width, y),
                new Point2(x + width, y + height), new Point2(x, y + height)
        };
        drawQuad(textureId, corners, u0, v0, u1, v1, targetWidth, targetHeight, 1.0f,
                true, true, 0, flipTarget, blend);
    }

    private void drawQuad(int textureId, Point2[] corners, float u0, float v0, float u1, float v1,
                          int targetWidth, int targetHeight, float opacity, boolean premultiplied,
                          boolean useTexture, int argb, boolean flipTarget, boolean blend) {
        if (!isUsable() || opacity <= 0.0f || corners.length < 4) {
            return;
        }

        // Save every piece of state we are about to change.
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int prevActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean prevDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int prevBlendSrcRgb = GL11.glGetInteger(GL20.GL_BLEND_SRC_RGB);
        int prevBlendDstRgb = GL11.glGetInteger(GL20.GL_BLEND_DST_RGB);
        int prevBlendSrcAlpha = GL11.glGetInteger(GL20.GL_BLEND_SRC_ALPHA);
        int prevBlendDstAlpha = GL11.glGetInteger(GL20.GL_BLEND_DST_ALPHA);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        // Minecraft 26.x binds sampler objects, which override per-texture
        // filtering. Unbind for our draw so the texture parameters apply.
        int prevSampler = GL11.glGetInteger(org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING);
        org.lwjgl.opengl.GL33.glBindSampler(0, 0);

        try {
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer buffer = stack.mallocFloat(16);
                putVertex(buffer, corners[0], u0, v0);   // top-left
                putVertex(buffer, corners[1], u1, v0);   // top-right
                putVertex(buffer, corners[3], u0, v1);   // bottom-left
                putVertex(buffer, corners[2], u1, v1);   // bottom-right
                buffer.flip();
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, buffer);
            }

            GL20.glUniform2f(uViewport, targetWidth, targetHeight);
            GL20.glUniform1i(uFlipTarget, flipTarget ? 1 : 0);
            GL20.glUniform1f(uOpacity, opacity);
            GL20.glUniform1i(uPremultiplied, premultiplied ? 1 : 0);
            GL20.glUniform1i(uUseTexture, useTexture ? 1 : 0);
            GL20.glUniform4f(uColor,
                    ((argb >> 16) & 0xFF) / 255.0f,
                    ((argb >> 8) & 0xFF) / 255.0f,
                    (argb & 0xFF) / 255.0f,
                    ((argb >>> 24) & 0xFF) / 255.0f);
            GL20.glUniform1i(uTex, 0);

            if (useTexture) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            }

            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (blend) {
                GL11.glEnable(GL11.GL_BLEND);
                // Source is premultiplied by the time it reaches the blender.
                GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            } else {
                GL11.glDisable(GL11.GL_BLEND);
            }

            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        } finally {
            org.lwjgl.opengl.GL33.glBindSampler(0, prevSampler);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);
            GL13.glActiveTexture(prevActiveTexture);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevVbo);
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL20.glBlendFuncSeparate(prevBlendSrcRgb, prevBlendDstRgb, prevBlendSrcAlpha, prevBlendDstAlpha);
            setEnabled(GL11.GL_BLEND, prevBlend);
            setEnabled(GL11.GL_DEPTH_TEST, prevDepth);
            setEnabled(GL11.GL_CULL_FACE, prevCull);
            setEnabled(GL11.GL_SCISSOR_TEST, prevScissor);
        }
    }

    private static void putVertex(FloatBuffer buffer, Point2 position, float u, float v) {
        buffer.put((float) position.x()).put((float) position.y()).put(u).put(v);
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }

    @Override
    public void close() {
        if (vbo != 0) {
            GL15.glDeleteBuffers(vbo);
            vbo = 0;
        }
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
        initialised = false;
    }
}
