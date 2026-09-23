package dev.streamable.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserRuntimeTest {

    private static final String TAG = "jcef-d3de827+cef-146.0.10+g8219561+chromium-146.0.7680.179";

    @TempDir
    Path dir;

    private void install(String tag, String platform, boolean lock, boolean library) throws IOException {
        Files.writeString(dir.resolve("build_meta.json"),
                "{\"release_tag\": \"" + tag + "\", \"platform\": \"" + platform + "\"}");
        if (lock) {
            Files.writeString(dir.resolve("install.lock"), "");
        }
        if (library) {
            Files.writeString(dir.resolve("libjcef.so"), "elf");
        }
    }

    @Test
    void acceptsTheExactPinnedBuild() throws IOException {
        install(TAG, "linux-amd64", true, true);
        assertDoesNotThrow(() -> BrowserRuntime.validate(dir, TAG, "linux-amd64", "libjcef.so"));
    }

    @Test
    void rejectsAnotherVersionSoItIsReinstalledVerified() throws IOException {
        install("jcef-other+cef-1.0", "linux-amd64", true, true);
        IOException error = assertThrows(IOException.class,
                () -> BrowserRuntime.validate(dir, TAG, "linux-amd64", "libjcef.so"));
        assertTrue(error.getMessage().contains("does not match"));
    }

    @Test
    void rejectsWrongPlatformMissingLockOrLibrary() throws IOException {
        install(TAG, "windows-amd64", true, true);
        assertThrows(IOException.class, () -> BrowserRuntime.validate(dir, TAG, "linux-amd64", "libjcef.so"));
        Files.delete(dir.resolve("install.lock"));
        install(TAG, "linux-amd64", false, true);
        assertThrows(IOException.class, () -> BrowserRuntime.validate(dir, TAG, "linux-amd64", "libjcef.so"));
        install(TAG, "linux-amd64", true, false);
        Files.delete(dir.resolve("libjcef.so"));
        assertThrows(IOException.class, () -> BrowserRuntime.validate(dir, TAG, "linux-amd64", "libjcef.so"));
    }
}
