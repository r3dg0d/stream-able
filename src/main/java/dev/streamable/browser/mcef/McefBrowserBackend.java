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

    private final CopyOnWriteArrayList<McefBrowserHandle> handles = new CopyOnWriteArrayList<>();

    private volatile MCEFApi api;
    private volatile BrowserEngineStatus status =
            BrowserEngineStatus.initialising("Not started", -1);
    private volatile boolean loadHandlerRegistered;
    private volatile org.cef.browser.CefMessageRouter audioRouter;
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
        if (!loadHandlerRegistered) {
            registerHandlersBeforeFirstBrowser(current);
        }

        // transparent = true is the first requirement for an overlay that does
        // not paint an opaque rectangle over the game; the CSS injection and the
        // compositor's premultiplied blending are the other two.
        // Created blank: the handle navigates to the real URL once the audio
        // tap is registered to run before the page's own scripts.
        MCEFBrowser browser = current.createBrowser("about:blank", true);
        browser.resize(Math.max(1, width), Math.max(1, height));

        McefBrowserHandle handle = new McefBrowserHandle(browser, target, width, height);
        handles.add(handle);
        try {
            registerLoadHandlerOnce(browser.getCefBrowser());
        } catch (RuntimeException e) {
            StreamAbleLog.BROWSER.warn("Could not attach load handler; CSS will be applied on demand only", e);
        }
        return handle;
    }

    /**
     * Registers the load handler and the audio message router on MCEF's shared
     * client before any real source exists.
     *
     * <p>JCEF decides which handlers a browser has - and which message routers
     * its renderer process knows - when that browser is created, so handlers
     * attached to the client afterwards never reach browsers that already
     * exist. MCEF does not expose its client, so a throwaway blank browser is
     * created to reach it, then closed; every source created afterwards gets
     * the handlers.</p>
     */
    private void registerHandlersBeforeFirstBrowser(MCEFApi current) {
        MCEFBrowser probe = null;
        try {
            probe = current.createBrowser("about:blank", true);
            registerLoadHandlerOnce(probe.getCefBrowser());
        } catch (RuntimeException e) {
            StreamAbleLog.BROWSER.warn("Could not prepare browser handlers; CSS, input and audio injection "
                    + "will be applied on demand only", e);
        } finally {
            if (probe != null) {
                try {
                    probe.close();
                } catch (RuntimeException e) {
                    StreamAbleLog.BROWSER.debug("Could not close the setup browser: {}", e.toString());
                }
            }
        }
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
            registerAudioRouter(cefBrowser);
            installLoadHandler(cefBrowser.getClient(), new CefLoadHandlerAdapter() {
                @Override
                public void onLoadStart(CefBrowser browser, CefFrame frame,
                                        org.cef.network.CefRequest.TransitionType transitionType) {
                    // As early as possible, so the tap is in place before the
                    // page creates its own audio; re-applied at load end.
                    if (frame != null && frame.isMain()) {
                        injectAudioTap(browser);
                    }
                }

                @Override
                public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                    if (frame == null || !frame.isMain()) {
                        return;   // sub-frames inherit styling from the main document
                    }
                    McefBrowserHandle handle = handleFor(browser);
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
                        injectAudioTap(browser);
                    } catch (RuntimeException e) {
                        StreamAbleLog.BROWSER.warn("Failed to inject browser source styling/shim", e);
                    }
                    handle.onMainFrameLoaded();
                }
            });
            loadHandlerRegistered = true;
            StreamAbleLog.BROWSER.debug("Browser load handler registered.");
        }
    }

    /**
     * Installs our load handler on the shared client. JCEF keeps a single
     * load-handler slot and silently ignores {@code addLoadHandler} when it is
     * taken, so an existing handler is chained rather than replaced.
     */
    private static void installLoadHandler(org.cef.CefClient client, org.cef.handler.CefLoadHandler ours) {
        org.cef.handler.CefLoadHandler previous = null;
        try {
            java.lang.reflect.Field field = org.cef.CefClient.class.getDeclaredField("loadHandler_");
            field.setAccessible(true);
            previous = (org.cef.handler.CefLoadHandler) field.get(client);
        } catch (ReflectiveOperationException | RuntimeException e) {
            StreamAbleLog.BROWSER.debug("Could not inspect the browser load handler: {}", e.toString());
        }
        if (previous == null) {
            client.addLoadHandler(ours);
            StreamAbleLog.BROWSER.info("Browser load handler installed.");
            return;
        }
        org.cef.handler.CefLoadHandler existing = previous;
        client.removeLoadHandler();
        client.addLoadHandler(new org.cef.handler.CefLoadHandler() {
            @Override
            public void onLoadingStateChange(CefBrowser b, boolean loading, boolean back, boolean forward) {
                existing.onLoadingStateChange(b, loading, back, forward);
                ours.onLoadingStateChange(b, loading, back, forward);
            }

            @Override
            public void onLoadStart(CefBrowser b, CefFrame f, org.cef.network.CefRequest.TransitionType t) {
                existing.onLoadStart(b, f, t);
                ours.onLoadStart(b, f, t);
            }

            @Override
            public void onLoadEnd(CefBrowser b, CefFrame f, int status) {
                existing.onLoadEnd(b, f, status);
                ours.onLoadEnd(b, f, status);
            }

            @Override
            public void onLoadError(CefBrowser b, CefFrame f, ErrorCode code, String text, String url) {
                existing.onLoadError(b, f, code, text, url);
                ours.onLoadError(b, f, code, text, url);
            }
        });
        StreamAbleLog.BROWSER.info("Browser load handler chained after an existing one ({}).",
                existing.getClass().getName());
    }

    private void injectAudioTap(CefBrowser browser) {
        McefBrowserHandle handle = handleFor(browser);
        if (handle == null || handle.isClosed()) {
            return;
        }
        String script = handle.audioScript();
        if (!script.isEmpty()) {
            try {
                browser.executeJavaScript(script, browser.getURL(), 0);
            } catch (RuntimeException e) {
                StreamAbleLog.BROWSER.debug("Could not inject the audio tap: {}", e.toString());
            }
        }
    }

    /**
     * The page-to-Java channel for the audio tap: a JCEF message router, which
     * defines {@code window.streamableAudioQuery} in every page. A client may
     * hold several routers, so this does not disturb MCEF's own.
     */
    private void registerAudioRouter(CefBrowser cefBrowser) {
        try {
            org.cef.browser.CefMessageRouter router = org.cef.browser.CefMessageRouter.create(
                    new org.cef.browser.CefMessageRouter.CefMessageRouterConfig(
                            dev.streamable.browser.audio.BrowserAudioTap.QUERY_FUNCTION,
                            dev.streamable.browser.audio.BrowserAudioTap.CANCEL_FUNCTION));
            router.addHandler(new org.cef.handler.CefMessageRouterHandlerAdapter() {
                @Override
                public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request,
                                       boolean persistent, org.cef.callback.CefQueryCallback callback) {
                    var chunk = dev.streamable.browser.audio.BrowserAudioTap.parse(request);
                    if (chunk == null) {
                        return false;   // not ours
                    }
                    McefBrowserHandle handle = handleFor(browser);
                    if (handle != null) {
                        handle.deliverAudio(chunk);
                    }
                    callback.success("");
                    return true;
                }
            }, true);
            cefBrowser.getClient().addMessageRouter(router);
            audioRouter = router;
            StreamAbleLog.BROWSER.info("Browser audio tap ready: page audio can reach recordings and streams.");
        } catch (RuntimeException | LinkageError e) {
            StreamAbleLog.BROWSER.warn("Browser audio capture unavailable (message router failed): {}", e.toString());
        }
    }

    public boolean audioCaptureReady() {
        return audioRouter != null;
    }

    /**
     * The handle for a browser in a CEF callback. Matched by object, falling
     * back to the browser identifier: an off-screen browser's identifier is
     * only assigned once its native side exists, so it cannot be used as a key
     * at creation time (keying by it is why load-time injection used to find
     * no handle).
     */
    private McefBrowserHandle handleFor(CefBrowser browser) {
        if (browser == null) {
            return null;
        }
        for (McefBrowserHandle handle : handles) {
            CefBrowser own = handle.mcefBrowser().getCefBrowser();
            if (own == browser || (own.getIdentifier() > 0 && own.getIdentifier() == browser.getIdentifier())) {
                return handle;
            }
        }
        return null;
    }

    /** Forgets a handle that the manager has closed. */
    public void forget(BrowserHandle handle) {
        if (handle instanceof McefBrowserHandle mcef) {
            handles.remove(mcef);
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
        // The CefApp itself is owned by MCEF, which disposes it on CLIENT_STOPPING.
    }
}
