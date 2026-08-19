package dev.streamable.browser.mcef;

import dev.streamable.StreamAbleLog;
import dev.streamable.browser.BrowserBackend;
import dev.streamable.browser.BrowserEngineStatus;
import dev.streamable.browser.BrowserHandle;
import dev.streamable.browser.css.BrowserCssInjector;
import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link BrowserBackend} implemented on MCEF Modern.
 *
 * <p>This class is only ever loaded when {@code mcef-modern} is actually
 * present - {@code BrowserSourceManager} checks with the Fabric loader first -
 * so a missing browser engine can never produce a {@code NoClassDefFoundError}
 * on a machine that just wants to record.</p>
 *
 * <h2>Load-time injection</h2>
 * <p>MCEF creates browsers from a single shared {@code CefClient}, which is
 * reachable through {@link CefBrowser#getClient()}. Registering one
 * {@code CefLoadHandler} there lets Stream-able re-apply the transparency CSS,
 * the user's custom CSS and the keyboard shim every time a page finishes
 * loading - including after a redirect or a widget's own reload, which is when
 * an overlay would otherwise go opaque.</p>
 */
public final class McefBrowserBackend implements BrowserBackend {

    /** Loaded once from the mod jar; see the file for what it does and why. */
    private static final String INPUT_SHIM = loadInputShim();

    private final Map<Integer, McefBrowserHandle> handlesByBrowserId = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<McefBrowserHandle> handles = new CopyOnWriteArrayList<>();

    private volatile MCEFApi api;
    private volatile BrowserEngineStatus status =
            BrowserEngineStatus.initialising("Not started", -1);
    private volatile boolean loadHandlerRegistered;
    private volatile boolean closed;

    private static String loadInputShim() {
        try (InputStream in = McefBrowserBackend.class
                .getResourceAsStream("/assets/streamable/browser/input-shim.js")) {
            if (in == null) {
                StreamAbleLog.BROWSER.error(
                        "Browser input shim resource is missing from the mod jar; "
                                + "editing keys may not work in browser sources.");
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            StreamAbleLog.BROWSER.error("Failed to read the browser input shim", e);
            return "";
        }
    }

    @Override
    public void beginInitialisation() {
        if (closed || api != null) {
            return;
        }
        MCEFApi.Initialization initialization = MCEFApi.initialize();
        status = BrowserEngineStatus.initialising(describe(initialization.getStage()),
                initialization.getPercentage());

        initialization.getFuture().whenComplete((instance, error) -> {
            if (error != null) {
                // Chromium failing must never take recording or streaming with it.
                // A native-load failure is diagnosed properly rather than echoed:
                // Linux blames the top-level library even when the real problem
                // is a missing dependency of it.
                String explanation = dev.streamable.browser.NativeLibraryDiagnostic.explain(error);
                if (explanation != null) {
                    StreamAbleLog.BROWSER.error(
                            "Browser engine unavailable - native libraries could not be loaded.\n{}",
                            explanation);
                    status = BrowserEngineStatus.failed(explanation);
                } else {
                    StreamAbleLog.BROWSER.error(
                            "MCEF initialisation failed; browser sources are unavailable", error);
                    status = BrowserEngineStatus.failed(rootMessage(error));
                }
                return;
            }
            api = instance;
            status = BrowserEngineStatus.ready();
            StreamAbleLog.BROWSER.info("Browser engine ready.");
        });
    }

    /** Refreshes progress text while initialisation is still running. */
    public void tickStatus() {
        if (closed || api != null) {
            return;
        }
        MCEFApi.Initialization initialization = MCEFApi.initialize();
        if (status.state() == BrowserEngineStatus.State.INITIALISING) {
            status = BrowserEngineStatus.initialising(describe(initialization.getStage()),
                    initialization.getPercentage());
        }
    }

    private static String describe(MCEFApi.Initialization.Stage stage) {
        return switch (stage) {
            case NOT_STARTED -> "Locating browser runtime";
            case DOWNLOADING -> "Downloading browser runtime";
            case EXTRACTING -> "Extracting browser runtime";
            case INSTALL -> "Installing browser runtime";
            case INITIALIZING -> "Starting Chromium";
            case DONE -> "Ready";
        };
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    @Override
    public BrowserEngineStatus status() {
        return status;
    }

    @Override
    public BrowserHandle createBrowser(String url, int width, int height) {
        MCEFApi current = api;
        if (current == null || closed) {
            throw new IllegalStateException("Browser engine is not ready");
        }
        String target = url == null || url.isBlank() ? "about:blank" : url.trim();

        // transparent = true is the first requirement for an overlay that does
        // not paint an opaque rectangle over the game; the CSS injection and the
        // compositor's premultiplied blending are the other two.
        MCEFBrowser browser = current.createBrowser(target, true);
        browser.resize(Math.max(1, width), Math.max(1, height));

        McefBrowserHandle handle = new McefBrowserHandle(browser, target, width, height);
        handles.add(handle);
        try {
            handlesByBrowserId.put(browser.getCefBrowser().getIdentifier(), handle);
            registerLoadHandlerOnce(browser.getCefBrowser());
        } catch (RuntimeException e) {
            StreamAbleLog.BROWSER.warn("Could not attach load handler; CSS will be applied on demand only", e);
        }
        return handle;
    }

    /**
     * Attaches the shared load handler the first time we see a real CefBrowser.
     *
     * <p>MCEF does not expose its {@code CefClient}, but every browser does via
     * the public {@code CefBrowser.getClient()}, and MCEF creates all browsers
     * from the same client - so one registration covers every source.</p>
     */
    private void registerLoadHandlerOnce(CefBrowser cefBrowser) {
        if (loadHandlerRegistered) {
            return;
        }
        synchronized (this) {
            if (loadHandlerRegistered) {
                return;
            }
            cefBrowser.getClient().addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                    if (frame == null || !frame.isMain()) {
                        return;   // sub-frames inherit styling from the main document
                    }
                    McefBrowserHandle handle = handlesByBrowserId.get(browser.getIdentifier());
                    if (handle == null || handle.isClosed()) {
                        return;
                    }
                    try {
                        // Re-assert transparency and user CSS: a redirect or a
                        // widget's own reload would otherwise drop them.
                        browser.executeJavaScript(
                                BrowserCssInjector.buildInjectionScript(handle.pendingCss()),
                                browser.getURL(), 0);
                        if (!INPUT_SHIM.isEmpty()) {
                            browser.executeJavaScript(INPUT_SHIM, browser.getURL(), 0);
                        }
                    } catch (RuntimeException e) {
                        StreamAbleLog.BROWSER.warn("Failed to inject browser source styling/shim", e);
                    }
                }
            });
            loadHandlerRegistered = true;
            StreamAbleLog.BROWSER.debug("Browser load handler registered.");
        }
    }

    /** Forgets a handle that the manager has closed. */
    public void forget(BrowserHandle handle) {
        if (handle instanceof McefBrowserHandle mcef) {
            handles.remove(mcef);
            handlesByBrowserId.values().remove(mcef);
        }
    }

    public int liveBrowserCount() {
        return handles.size();
    }

    @Override
    public void close() {
        closed = true;
        for (McefBrowserHandle handle : handles) {
            try {
                handle.close();
            } catch (RuntimeException e) {
                StreamAbleLog.BROWSER.warn("Error closing browser during shutdown", e);
            }
        }
        handles.clear();
        handlesByBrowserId.clear();
        // The CefApp itself is owned by MCEF, which disposes it on CLIENT_STOPPING.
    }
}
