package dev.streamable.browser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NativeLibraryDiagnosticTest {

    private static final String REAL_MESSAGE =
            "/home/user/.local/share/PrismLauncher/instances/Kitchen Sink 26.1.2"
                    + "/minecraft/config/mcef-modern/jcef/libjcef.so: cannot open shared object file:"
                    + " No such file or directory";

    @Test
    @DisplayName("parses the library path even when it contains spaces")
    void parsesPathWithSpaces() {
        Path parsed = NativeLibraryDiagnostic.extractLibraryPath(REAL_MESSAGE);
        assertNotNull(parsed);
        assertEquals("libjcef.so", parsed.getFileName().toString());
        assertTrue(parsed.toString().contains("Kitchen Sink 26.1.2"));
    }

    @Test
    void parsesVersionedSoNames() {
        Path parsed = NativeLibraryDiagnostic.extractLibraryPath(
                "/usr/lib/libvulkan.so.1: cannot open shared object file: No such file or directory");
        assertNotNull(parsed);
        assertEquals("libvulkan.so.1", parsed.getFileName().toString());
    }

    @Test
    @DisplayName("Windows DLL paths are parsed too, drive letter and all")
    void parsesWindowsDllPaths() {
        Path parsed = NativeLibraryDiagnostic.extractLibraryPath(
                "C:\\Users\\bob\\AppData\\Roaming\\.minecraft\\config\\mcef-modern\\jcef\\jcef.dll:"
                        + " Can't find dependent libraries");
        assertNotNull(parsed);
        // Asserted on the whole string rather than getFileName(): a Linux JVM
        // does not treat backslashes as separators, so the segment split is
        // platform dependent while the extraction itself is not.
        assertTrue(parsed.toString().endsWith("jcef.dll"), parsed.toString());
        assertTrue(parsed.toString().startsWith("C:"), parsed.toString());
    }

    @Test
    void parsesMacDylibPaths() {
        Path parsed = NativeLibraryDiagnostic.extractLibraryPath(
                "/Users/bob/config/mcef-modern/jcef/libjcef.dylib: image not found");
        assertNotNull(parsed);
        assertEquals("libjcef.dylib", parsed.getFileName().toString());
    }

    @Test
    void nativeLibraryNameMatchesThePlatform() {
        String name = NativeLibraryDiagnostic.nativeLibraryFileName();
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            assertEquals("jcef.dll", name);
        } else if (os.contains("mac")) {
            assertEquals("libjcef.dylib", name);
        } else {
            assertEquals("libjcef.so", name);
        }
    }

    @Test
    void returnsNullWithoutALibraryPath() {
        assertNull(NativeLibraryDiagnostic.extractLibraryPath("something else went wrong"));
    }

    @Test
    @DisplayName("non-native failures are left to the normal error path")
    void ignoresUnrelatedFailures() {
        assertNull(NativeLibraryDiagnostic.explain(new RuntimeException("network down")));
    }

    @Test
    void findsTheLinkErrorThroughAWrappedChain() {
        Throwable wrapped = new RuntimeException("init failed",
                new IllegalStateException("stage 2", new UnsatisfiedLinkError(REAL_MESSAGE)));
        String explanation = NativeLibraryDiagnostic.explain(wrapped);
        assertNotNull(explanation);
        assertTrue(explanation.toLowerCase().contains("browser"));
    }

    @Test
    @DisplayName("a genuinely absent file is reported as absent, not as a dependency problem")
    void missingFileIsReportedDirectly() {
        String explanation = NativeLibraryDiagnostic.explain(
                new UnsatisfiedLinkError("/nonexistent/path/libjcef.so: cannot open shared object file:"
                        + " No such file or directory"));
        assertNotNull(explanation);
        assertTrue(explanation.contains("missing"), explanation);
        assertTrue(explanation.contains("download"), explanation);
    }

    @Test
    void missingDependenciesReturnsEmptyForANonLibrary() {
        // ldd on something that is not an ELF object must not throw.
        assertNotNull(NativeLibraryDiagnostic.missingDependencies(Path.of("/etc/hostname")));
    }
}
