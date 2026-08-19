package dev.streamable.browser;

/**
 * Creates and owns browser instances.
 *
 * <p>Initialisation is asynchronous: the game must never block waiting for
 * Chromium to download and start. Callers poll {@link #status()} and create
 * browsers only once it reports {@link BrowserEngineStatus.State#READY}.</p>
 */
public interface BrowserBackend extends AutoCloseable {

    /** Begins asynchronous initialisation. Safe to call repeatedly. */
    void beginInitialisation();

    BrowserEngineStatus status();

    /**
     * Creates a transparent off-screen browser.
     *
     * @throws IllegalStateException if the backend is not {@link BrowserEngineStatus.State#READY}
     */
    BrowserHandle createBrowser(String url, int width, int height);

    /** Releases the engine and every browser it created. */
    @Override
    void close();
}
