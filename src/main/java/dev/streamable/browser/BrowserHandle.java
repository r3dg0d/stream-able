package dev.streamable.browser;

/**
 * A live off-screen browser owned by Stream-able.
 *
 * <p>Deliberately expressed in primitives (GLFW key codes, canvas-local pixel
 * coordinates, a raw GL texture name) rather than Minecraft or JCEF types, so
 * the compositor, the input router and the tests do not depend on which engine
 * is underneath. {@code McefBrowserHandle} is currently the only
 * implementation, but the seam means MCEF can be patched or replaced without
 * touching the rest of the mod.</p>
 *
 * <p><b>Threading:</b> every method must be called from the Minecraft render or
 * client thread. Chromium's own paint callbacks are marshalled internally; no
 * GL call is ever made from a CEF thread.</p>
 */
public interface BrowserHandle extends AutoCloseable {

    /** Resizes the off-screen viewport. Cheap to call with unchanged values. */
    void resize(int width, int height);

    void loadUrl(String url);

    String currentUrl();

    void reload(boolean ignoreCache);

    /** Focus controls whether Chromium routes keyboard input into the page. */
    void setFocus(boolean focused);

    /** Caps Chromium's repaint rate; a static widget does not need 240 Hz. */
    void setFrameRate(int fps);

    void executeJavaScript(String code);

    /** Applies the user's custom CSS plus the transparency guarantees. */
    void applyCss(String css);

    /** Receives the page's audio captured by the in-page tap (CEF threads; must not block). */
    @FunctionalInterface
    interface AudioSink {
        void accept(int stream, int sampleRate, int channels, short[] samples);
    }

    /**
     * Sets where the page's audio goes: heard locally, captured for outputs,
     * both or neither, at a volume. Applied now and after every page load.
     */
    default void configureAudio(dev.streamable.source.BrowserAudioMode mode, float volume) {
    }

    default void setAudioSink(AudioSink sink) {
    }

    // ---- input (coordinates are source-local pixels) -----------------------

    void mouseMoved(double localX, double localY);

    void mousePressed(double localX, double localY, int button, int modifiers, boolean doubleClick);

    void mouseReleased(double localX, double localY, int button, int modifiers);

    void mouseScrolled(double localX, double localY, double amount);

    void keyPressed(int glfwKey, int scancode, int modifiers);

    void keyReleased(int glfwKey, int scancode, int modifiers);

    void charTyped(int codepoint);

    /**
     * Hands Minecraft's clipboard contents to the page so the paste fallback can
     * use them if Chromium's own paste does not take effect.
     */
    void setClipboardHint(String text);

    // ---- rendering ---------------------------------------------------------

    /** Raw OpenGL texture name holding the latest painted frame, or {@code 0}. */
    int textureId();

    /** Whether at least one frame has been painted. */
    boolean hasFrame();

    int textureWidth();

    int textureHeight();

    @Override
    void close();
}
