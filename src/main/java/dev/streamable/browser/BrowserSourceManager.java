package dev.streamable.browser;

import dev.streamable.StreamAbleLog;
import dev.streamable.source.BrowserSource;
import dev.streamable.source.SourceList;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns the live browser for each configured {@link BrowserSource}.
 *
 * <p>Configuration and runtime are deliberately separate: a {@code BrowserSource}
 * is a plain persisted model that exists whether or not Chromium is running,
 * and this manager reconciles it against reality on every client tick. That
 * means sources can be created, edited and saved while the engine is still
 * downloading, and it makes engine failure a non-event for the rest of the mod.</p>
 *
 * <p><b>Threading:</b> every method must run on the Minecraft client/render
 * thread, which is the only thread allowed to touch GL objects or MCEF.</p>
 */
public final class BrowserSourceManager implements AutoCloseable {

    /** The mod id of the browser engine, kept in one place. */
    public static final String MCEF_MOD_ID = "mcef-modern";

    private final Map<UUID, BrowserHandle> handles = new HashMap<>();

    /** Receives captured page audio, tagged with its source (CEF threads; must not block). */
    @FunctionalInterface
    public interface SourceAudioSink {
        void accept(UUID source, int stream, int sampleRate, int channels, short[] samples);
    }

    private volatile SourceAudioSink audioSink;

    public void setAudioSink(SourceAudioSink sink) {
        this.audioSink = sink;
    }
    /** Remembers what we last pushed, so ticks are cheap no-ops when nothing changed. */
    private final Map<UUID, AppliedState> applied = new HashMap<>();

    private BrowserBackend backend;
    private BrowserEngineStatus statusWhenUnavailable = BrowserEngineStatus.NOT_INSTALLED;
    private boolean initialisationRequested;
    private BrowserRuntime runtime;
    private volatile boolean runtimeReady;

    private record AppliedState(String url, int width, int height, String css, int fps,
                                dev.streamable.source.BrowserAudioMode audioMode, float audioVolume) {
    }

    /** Whether the browser engine mod is installed at all. */
    public static boolean isEngineInstalled() {
        return FabricLoader.getInstance().isModLoaded(MCEF_MOD_ID);
    }

    /**
     * Where MCEF puts its native library.
     *
     * <p>Mirrors MCEF's own layout ({@code <config>/mcef-modern/jcef}), with the
     * platform's native library name. It may not exist yet on a first run,
     * which the preloader handles.</p>
     */
    private static java.nio.file.Path jcefLibraryPath() {
        return mcefJcefDirectory().resolve(NativeLibraryDiagnostic.nativeLibraryFileName());
    }

    /** MCEF's native directory ({@code <config>/mcef-modern/jcef}). */
    public static java.nio.file.Path mcefJcefDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve(MCEF_MOD_ID).resolve("jcef");
    }

    /**
     * Starts the browser engine: first the verified Chromium natives through
     * the runtime manager (off the render thread), then MCEF itself on the
     * client thread once they are in place.
     *
     * <p>Nothing here can take recording, streaming or the game down: every
     * failure becomes a status line and browser sources show a placeholder.</p>
     *
     * @param runtime    the managed JCEF natives
     * @param enabled    the player's "browser sources" preference
     * @param autoInstall whether a missing runtime may be downloaded now
     * @param mainThread executor that runs work on the client thread
     */
    public void initialise(BrowserRuntime runtime, boolean enabled, boolean autoInstall,
                           java.util.concurrent.Executor mainThread) {
        if (initialisationRequested) {
            return;
        }
        this.runtime = runtime;
        if (!isEngineInstalled()) {
            StreamAbleLog.BROWSER.warn("The bundled MCEF Modern is missing from this installation - "
                    + "browser sources disabled. Recording and streaming are unaffected.");
            statusWhenUnavailable = BrowserEngineStatus.failed(
                    "The bundled browser integration (MCEF Modern) is missing from this installation.");
            return;
        }
        if (!enabled) {
            statusWhenUnavailable = BrowserEngineStatus.disabled();
            return;
        }
        if (runtime.artifact().isEmpty()) {
            statusWhenUnavailable = BrowserEngineStatus.failed(runtime.progress().detail());
            return;
        }
        initialisationRequested = true;
        if (!runtime.isInstalled() && !autoInstall) {
            statusWhenUnavailable = BrowserEngineStatus.failed(
                    "Browser engine not installed. Automatic runtime installation is off; install it from Runtime.");
            initialisationRequested = false;
            return;
        }
        runtime.ensureReady().whenComplete((directory, error) -> {
            if (error != null) {
                statusWhenUnavailable = BrowserEngineStatus.failed(runtime.progress().detail());
                initialisationRequested = false;   // allow a retry from the Runtime page
                return;
            }
            runtimeReady = true;
            mainThread.execute(this::startEngine);
        });
    }

    /** Legacy entry point without runtime management; kept for tests and tooling. */
    public void initialise() {
        statusWhenUnavailable = BrowserEngineStatus.failed("Browser engine was not started.");
    }

    /** Retries after a failure (Runtime page). */
    public void retry(boolean autoInstall, java.util.concurrent.Executor mainThread) {
        if (backend != null || runtime == null) {
            return;
        }
        initialisationRequested = false;
        initialise(runtime, true, true, mainThread);
    }

    private void startEngine() {
        if (backend != null) {
            return;
        }
        try {
            // Must happen before any MCEF class is touched: on systems without a
            // standard library layout, Chromium's dependencies have to be in the
            // process image before libjcef.so is loaded.
            NativeLibraryPreloader.preloadIfNeeded(jcefLibraryPath());

            backend = new dev.streamable.browser.mcef.McefBrowserBackend();
            backend.beginInitialisation();
        } catch (Throwable t) {
            // Includes LinkageError if MCEF is present but incompatible.
            StreamAbleLog.BROWSER.error("Could not start the browser engine; browser sources are unavailable", t);
            backend = null;
            statusWhenUnavailable = BrowserEngineStatus.failed(t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
        }
    }

    public BrowserEngineStatus status() {
        if (backend == null) {
            if (runtime != null && runtime.state().isBusy()) {
                return BrowserEngineStatus.fromRuntime(runtime.progress());
            }
            if (runtimeReady) {
                return BrowserEngineStatus.initialising("Starting Chromium", -1);
            }
            return statusWhenUnavailable;
        }
        if (backend instanceof dev.streamable.browser.mcef.McefBrowserBackend mcef) {
            mcef.tickStatus();
        }
        return backend.status();
    }

    public boolean isReady() {
        return backend != null && backend.status().isReady();
    }

    /** The live browser for a source, or {@code null} if it has none yet. */
    public BrowserHandle handleFor(UUID sourceId) {
        return handles.get(sourceId);
    }

    /**
     * Reconciles live browsers with the configured sources.
     *
     * <p>Creates browsers that should exist, updates the ones whose URL, size,
     * CSS or frame rate changed, and destroys those belonging to deleted
     * sources or to hidden sources configured to shut down.</p>
     */
    public void tick(SourceList sources) {
        Objects.requireNonNull(sources, "sources");
        List<BrowserSource> current = sources.snapshot();

        // 1. Drop browsers whose source no longer exists or should be released.
        List<UUID> toRemove = new ArrayList<>();
        for (Map.Entry<UUID, BrowserHandle> entry : handles.entrySet()) {
            BrowserSource source = findById(current, entry.getKey());
            if (source == null || (source.shutdownWhenHidden() && !source.visible())) {
                toRemove.add(entry.getKey());
            }
        }
        for (UUID id : toRemove) {
            destroy(id);
        }

        if (!isReady()) {
            return;   // sources will show a placeholder until the engine is up
        }

        // 2. Create or update the rest.
        for (BrowserSource source : current) {
            if (source.shutdownWhenHidden() && !source.visible()) {
                continue;
            }
            BrowserHandle handle = handles.get(source.id());
            if (handle == null) {
                create(source);
            } else {
                update(source, handle);
            }
        }
    }

    private static BrowserSource findById(List<BrowserSource> sources, UUID id) {
        for (BrowserSource source : sources) {
            if (source.id().equals(id)) {
                return source;
            }
        }
        return null;
    }

    private void create(BrowserSource source) {
        try {
            BrowserHandle handle = backend.createBrowser(source.url(),
                    source.viewportWidth(), source.viewportHeight());
            handle.setFrameRate(source.browserFps());
            handle.applyCss(source.customCss());
            UUID id = source.id();
            handle.setAudioSink((stream, rate, channels, samples) -> {
                SourceAudioSink sink = audioSink;
                if (sink != null) {
                    sink.accept(id, stream, rate, channels, samples);
                }
            });
            handle.configureAudio(source.audioMode(), source.audioVolume());
            handles.put(source.id(), handle);
            applied.put(source.id(), new AppliedState(source.url(),
                    source.viewportWidth(), source.viewportHeight(),
                    source.customCss(), source.browserFps(), source.audioMode(), source.audioVolume()));
            StreamAbleLog.BROWSER.info("Created browser source '{}' ({}x{})",
                    source.name(), source.viewportWidth(), source.viewportHeight());
        } catch (RuntimeException e) {
            StreamAbleLog.BROWSER.error("Failed to create browser source '{}'", source.name(), e);
        }
    }

    private void update(BrowserSource source, BrowserHandle handle) {
        AppliedState last = applied.get(source.id());
        int width = source.viewportWidth();
        int height = source.viewportHeight();
        if (last != null && last.width() == width && last.height() == height
                && last.url().equals(source.url()) && last.css().equals(source.customCss())
                && last.fps() == source.browserFps() && last.audioMode() == source.audioMode()
                && last.audioVolume() == source.audioVolume()) {
            return;   // nothing changed: do not touch Chromium at all
        }
        try {
            if (last == null || last.width() != width || last.height() != height) {
                handle.resize(width, height);
            }
            if (last == null || !last.url().equals(source.url())) {
                handle.loadUrl(source.url());
            }
            if (last == null || !last.css().equals(source.customCss())) {
                handle.applyCss(source.customCss());
            }
            if (last == null || last.fps() != source.browserFps()) {
                handle.setFrameRate(source.browserFps());
            }
            if (last == null || last.audioMode() != source.audioMode() || last.audioVolume() != source.audioVolume()) {
                handle.configureAudio(source.audioMode(), source.audioVolume());
            }
            applied.put(source.id(), new AppliedState(source.url(), width, height,
                    source.customCss(), source.browserFps(), source.audioMode(), source.audioVolume()));
        } catch (RuntimeException e) {
            StreamAbleLog.BROWSER.error("Failed to update browser source '{}'", source.name(), e);
        }
    }

    /** Forces a reload, e.g. from the Refresh button. */
    public void refresh(UUID sourceId, boolean ignoreCache) {
        BrowserHandle handle = handles.get(sourceId);
        if (handle != null) {
            handle.reload(ignoreCache);
        }
    }

    /** Destroys the browser for a source, releasing its native resources. */
    public void destroy(UUID sourceId) {
        BrowserHandle handle = handles.remove(sourceId);
        applied.remove(sourceId);
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (RuntimeException e) {
            StreamAbleLog.BROWSER.warn("Error closing browser source", e);
        }
        if (backend instanceof dev.streamable.browser.mcef.McefBrowserBackend mcef) {
            mcef.forget(handle);
        }
    }

    public int liveBrowserCount() {
        return handles.size();
    }

    @Override
    public void close() {
        for (UUID id : List.copyOf(handles.keySet())) {
            destroy(id);
        }
        if (backend != null) {
            backend.close();
            backend = null;
        }
    }
}
