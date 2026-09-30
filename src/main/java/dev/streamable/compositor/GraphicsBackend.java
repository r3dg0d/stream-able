package dev.streamable.compositor;

import dev.streamable.StreamAbleLog;
import org.lwjgl.opengl.GL;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether Minecraft is rendering with OpenGL, which the compositor and the
 * frame capture require.
 *
 * <p>Since 26.2 Minecraft can render with Vulkan instead (Video Settings &gt;
 * Graphics API), and it also falls back to Vulkan when it cannot open an OpenGL
 * context. Stream-able reads the frame through raw OpenGL calls; on a Vulkan
 * client there is no GL context, and LWJGL treats such a call as fatal, so it
 * would abort the whole JVM. Every OpenGL path is therefore gated on this check
 * instead: Stream-able stays out of the render loop and says why.</p>
 *
 * <p>The check asks LWJGL whether the render thread has GL capabilities. That
 * holds for the OpenGL backend and not for Vulkan, and it does not depend on any
 * Minecraft-internal class, so it behaves the same on every supported version.</p>
 */
public final class GraphicsBackend {

    /** Shown in Stream Health and logged once; names the setting that fixes it. */
    public static final String UNSUPPORTED_MESSAGE =
            "Minecraft is not rendering with OpenGL (it is using Vulkan), and Stream-able captures the game "
                    + "through OpenGL, so recording, streaming and browser sources are unavailable. "
                    + "Set Options > Video Settings > Graphics API to \"Prefer OpenGL\" and restart the game.";

    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    private GraphicsBackend() {
    }

    /** True when the calling (render) thread has an OpenGL context. */
    public static boolean openGlAvailable() {
        try {
            return GL.getCapabilities() != null;
        } catch (IllegalStateException | NullPointerException | LinkageError e) {
            // LinkageError: LWJGL's GL class cannot even load (no libGL), so there is no context either.
            return false;
        }
    }

    /**
     * Same as {@link #openGlAvailable()}, and on the first failure writes the
     * reason to the log so the player is not left guessing.
     */
    public static boolean checkOpenGl() {
        boolean available = openGlAvailable();
        if (!available && LOGGED.compareAndSet(false, true)) {
            StreamAbleLog.COMPOSITOR.warn(UNSUPPORTED_MESSAGE);
        }
        return available;
    }

    /** The user-facing reason Stream-able cannot capture, or {@code null} when OpenGL is in use. */
    public static String unsupportedReason() {
        return LOGGED.get() ? UNSUPPORTED_MESSAGE : null;
    }
}
