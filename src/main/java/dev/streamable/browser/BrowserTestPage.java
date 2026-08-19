package dev.streamable.browser;

import dev.streamable.StreamAbleLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Makes the bundled MCEF regression test page loadable as a browser source.
 *
 * <p>CEF cannot open a URL inside the mod jar, so the page is extracted to the
 * config directory and referenced with a {@code file:} URL. It is rewritten on
 * every call so an updated mod always ships an updated page.</p>
 *
 * <p>The page exercises transparency, clicks, typing, Backspace/Enter (the keys
 * MCEF issue #4 breaks), scrolling, clipboard shortcuts, a canvas animation and
 * audio playback - i.e. everything that is hard to verify by reasoning alone.</p>
 */
public final class BrowserTestPage {

    private static final String RESOURCE = "/assets/streamable/browser/test-page.html";
    public static final String FILE_NAME = "browser-test-page.html";

    private BrowserTestPage() {
    }

    /**
     * Extracts the page and returns a {@code file:} URL for it.
     *
     * @return the URL, or {@code null} if the page could not be written
     */
    public static String extractAndGetUrl(Path configDirectory) {
        Path target = configDirectory.resolve("stream-able").resolve(FILE_NAME);
        try (InputStream in = BrowserTestPage.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                StreamAbleLog.BROWSER.warn("Browser test page is missing from the mod jar.");
                return null;
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target.toUri().toString();
        } catch (IOException e) {
            StreamAbleLog.BROWSER.warn("Could not write the browser test page", e);
            return null;
        }
    }
}
