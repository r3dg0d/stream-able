package dev.streamable.browser.mcef;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.streamable.StreamAbleLog;
import dev.streamable.browser.BrowserHandle;
import dev.streamable.browser.css.BrowserCssInjector;
import dev.streamable.browser.input.BrowserKeyboardCompat;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link BrowserHandle} backed by MCEF Modern.
 *
 * <p>All the MCEF- and JCEF-specific knowledge lives here so the rest of
 * Stream-able only sees the neutral interface. In particular this class owns
 * the workaround for MCEF issue #4 (see {@link BrowserKeyboardCompat}).</p>
 */
public final class McefBrowserHandle implements BrowserHandle {

    private final MCEFBrowser browser;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile String url;
    private volatile String pendingCss = "";
    private int width;
    private int height;
    private int frameRate = -1;

    McefBrowserHandle(MCEFBrowser browser, String url, int width, int height) {
        this.browser = browser;
        this.url = url;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    MCEFBrowser mcefBrowser() {
        return browser;
    }

    /** CSS to re-apply whenever the page finishes loading. */
    String pendingCss() {
        return pendingCss;
    }

    @Override
    public void resize(int newWidth, int newHeight) {
        int w = Math.clamp(newWidth, 1, 16384);
        int h = Math.clamp(newHeight, 1, 16384);
        if (w == width && h == height) {
            return;   // resizing forces a full repaint; never do it needlessly
        }
        width = w;
        height = h;
        if (!closed.get()) {
            browser.resize(w, h);
        }
    }

    @Override
    public void loadUrl(String newUrl) {
        if (newUrl == null || newUrl.isBlank() || closed.get()) {
            return;
        }
        this.url = newUrl.trim();
        browser.getCefBrowser().loadURL(this.url);
    }

    @Override
    public String currentUrl() {
        if (closed.get()) {
            return url;
        }
        try {
            String actual = browser.getCefBrowser().getURL();
            return actual == null || actual.isBlank() ? url : actual;
        } catch (RuntimeException e) {
            StreamAbleLog.BROWSER.debug("Could not read browser URL", e);
            return url;
        }
    }

    @Override
    public void reload(boolean ignoreCache) {
        if (closed.get()) {
            return;
        }
        if (ignoreCache) {
            browser.getCefBrowser().reloadIgnoreCache();
        } else {
            browser.getCefBrowser().reload();
        }
    }

    @Override
    public void setFocus(boolean focused) {
        if (!closed.get()) {
            browser.setFocus(focused);
        }
    }

    @Override
    public void setFrameRate(int fps) {
        int clamped = Math.clamp(fps, 1, 240);
        if (clamped == frameRate || closed.get()) {
            return;
        }
        frameRate = clamped;
        try {
            browser.getCefBrowser().setWindowlessFrameRate(clamped);
        } catch (RuntimeException e) {
            StreamAbleLog.BROWSER.warn("Browser frame-rate limit not applied: {}", e.toString());
        }
    }

    @Override
    public void executeJavaScript(String code) {
        if (code == null || code.isBlank() || closed.get()) {
            return;
        }
        try {
            browser.getCefBrowser().executeJavaScript(code, currentUrl(), 0);
        } catch (RuntimeException e) {
            StreamAbleLog.BROWSER.warn("Failed to execute script in browser source", e);
        }
    }

    @Override
    public void applyCss(String css) {
        this.pendingCss = css == null ? "" : css;
        executeJavaScript(BrowserCssInjector.buildInjectionScript(this.pendingCss));
    }

    // ---- input -------------------------------------------------------------

    private static MouseButtonEvent mouse(double x, double y, int button, int modifiers) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, modifiers));
    }

    @Override
    public void mouseMoved(double localX, double localY) {
        if (!closed.get()) {
            browser.onMouseMoved((int) Math.round(localX), (int) Math.round(localY));
        }
    }

    @Override
    public void mousePressed(double localX, double localY, int button, int modifiers, boolean doubleClick) {
        if (!closed.get()) {
            browser.onMouseClicked(mouse(localX, localY, button, modifiers), doubleClick);
        }
    }

    @Override
    public void mouseReleased(double localX, double localY, int button, int modifiers) {
        if (!closed.get()) {
            browser.onMouseReleased(mouse(localX, localY, button, modifiers));
        }
    }

    @Override
    public void mouseScrolled(double localX, double localY, double amount) {
        if (!closed.get()) {
            browser.onMouseScrolled((int) Math.round(localX), (int) Math.round(localY), amount);
        }
    }

    /**
     * Forwards a key press, plus the character event a real keyboard would also
     * have produced.
     *
     * <p>This is the native half of the issue&nbsp;#4 workaround: MCEF maps
     * {@code onKeyPressed} to {@code KEYEVENT_RAWKEYDOWN} only, which does not
     * reach Blink's editing commands, while {@code onCharTyped} maps to
     * {@code KEYEVENT_CHAR} - exactly what {@code WM_CHAR} would deliver for
     * Backspace ({@code 0x08}) and Enter ({@code 0x0D}).</p>
     */
    @Override
    public void keyPressed(int glfwKey, int scancode, int modifiers) {
        if (closed.get()) {
            return;
        }
        browser.onKeyPressed(new KeyEvent(glfwKey, scancode, modifiers));
        int codepoint = BrowserKeyboardCompat.syntheticCodepoint(glfwKey, modifiers);
        if (codepoint != BrowserKeyboardCompat.NO_CHARACTER) {
            browser.onCharTyped(new CharacterEvent(codepoint));
        }
    }

    @Override
    public void keyReleased(int glfwKey, int scancode, int modifiers) {
        if (!closed.get()) {
            browser.onKeyReleased(new KeyEvent(glfwKey, scancode, modifiers));
        }
    }

    @Override
    public void charTyped(int codepoint) {
        if (!closed.get()) {
            browser.onCharTyped(new CharacterEvent(codepoint));
        }
    }

    @Override
    public void setClipboardHint(String text) {
        executeJavaScript(BrowserCssInjector.buildClipboardScript(text));
    }

    // ---- rendering ---------------------------------------------------------

    @Override
    public int textureId() {
        if (closed.get()) {
            return 0;
        }
        GpuTexture texture = browser.getTexture();
        // MCEF always allocates through the OpenGL device, but guard the cast
        // so a future backend change degrades to "no frame" instead of crashing.
        return texture instanceof GlTexture gl ? gl.glId() : 0;
    }

    @Override
    public boolean hasFrame() {
        return !closed.get() && browser.getTexture() != null;
    }

    @Override
    public int textureWidth() {
        GpuTexture texture = closed.get() ? null : browser.getTexture();
        return texture == null ? width : texture.getWidth(0);
    }

    @Override
    public int textureHeight() {
        GpuTexture texture = closed.get() ? null : browser.getTexture();
        return texture == null ? height : texture.getHeight(0);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                browser.close();
            } catch (RuntimeException e) {
                StreamAbleLog.BROWSER.warn("Error while closing browser source", e);
            }
        }
    }

    public boolean isClosed() {
        return closed.get();
    }
}
