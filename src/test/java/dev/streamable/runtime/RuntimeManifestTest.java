package dev.streamable.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeManifestTest {

    private static final String SHA = "a".repeat(64);

    private static String manifest(String url, String sha, int schema) {
        return """
                {"schema": %d, "components": {"demo": {"displayName": "Demo", "version": "1.2.3",
                 "artifacts": [
                   {"platform": "linux-x86_64", "urls": ["%s"], "sha256": "%s", "size": 10,
                    "format": "tar.xz", "fileName": "demo.tar.xz", "stripComponents": 1,
                    "include": ["bin/*"], "executables": ["bin/demo"]},
                   {"platform": "any", "urls": ["%s"], "sha256": "%s", "format": "file", "fileName": "demo.bin"}
                 ]}}}
                """.formatted(schema, url, sha, url, sha);
    }

    @Test
    void bundledManifestParsesAndPinsEveryArtifact() {
        RuntimeManifest manifest = RuntimeManifest.loadBundled();
        for (String id : new String[]{"ffmpeg", "jcef-natives", "onnxruntime",
                "model-dpdfnet2-48k", "model-dfn2-16k", "model-gtcrn-16k"}) {
            RuntimeDescriptor descriptor = manifest.require(id);
            assertFalse(descriptor.artifacts().isEmpty(), id);
            for (RuntimeArtifact artifact : descriptor.artifacts()) {
                assertEquals(64, artifact.sha256().length(), id);
                assertTrue(artifact.size() > 0, id + " must pin its size");
                artifact.urls().forEach(url -> assertEquals("https", url.getScheme()));
            }
        }
        // The two platforms the release promises.
        assertTrue(manifest.require("ffmpeg").supports(RuntimePlatform.LINUX_X86_64));
        assertTrue(manifest.require("ffmpeg").supports(RuntimePlatform.WINDOWS_X86_64));
        assertTrue(manifest.require("jcef-natives").supports(RuntimePlatform.LINUX_X86_64));
        assertTrue(manifest.require("jcef-natives").supports(RuntimePlatform.WINDOWS_X86_64));
    }

    @Test
    void parsesArtifactFields() {
        RuntimeManifest manifest = RuntimeManifest.parse(manifest("https://example.org/demo", SHA, 1));
        RuntimeDescriptor demo = manifest.require("demo");
        RuntimeArtifact linux = demo.artifactFor(RuntimePlatform.LINUX_X86_64).orElseThrow();
        assertEquals(RuntimeArtifact.Format.TAR_XZ, linux.format());
        assertEquals(1, linux.stripComponents());
        assertTrue(linux.shouldInclude("bin/demo"));
        assertFalse(linux.shouldInclude("share/doc/readme"));
        assertTrue(linux.isExecutable("bin/demo"));
    }

    @Test
    void platformSelectionPrefersExactMatchThenAny() {
        RuntimeDescriptor demo = RuntimeManifest.parse(manifest("https://example.org/demo", SHA, 1)).require("demo");
        assertEquals(RuntimePlatform.LINUX_X86_64, demo.artifactFor(RuntimePlatform.LINUX_X86_64).orElseThrow().platform());
        assertEquals(RuntimePlatform.ANY, demo.artifactFor(RuntimePlatform.WINDOWS_X86_64).orElseThrow().platform());
    }

    @Test
    void rejectsPlainHttp() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeManifest.parse(manifest("http://example.org/demo", SHA, 1)));
    }

    @Test
    void rejectsMalformedDigest() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeManifest.parse(manifest("https://example.org/demo", "abc123", 1)));
    }

    @Test
    void rejectsUnknownSchema() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeManifest.parse(manifest("https://example.org/demo", SHA, 2)));
    }

    @Test
    void rejectsPathLikeFileNames() {
        String json = manifest("https://example.org/demo", SHA, 1).replace("\"demo.bin\"", "\"../evil.bin\"");
        assertThrows(IllegalArgumentException.class, () -> RuntimeManifest.parse(json));
    }

    @Test
    void platformDetection() {
        assertEquals(RuntimePlatform.LINUX_X86_64, RuntimePlatform.detect("Linux", "amd64"));
        assertEquals(RuntimePlatform.WINDOWS_X86_64, RuntimePlatform.detect("Windows 11", "amd64"));
        assertEquals(new RuntimePlatform(RuntimePlatform.Os.MACOS, RuntimePlatform.Arch.AARCH64),
                RuntimePlatform.detect("Mac OS X", "aarch64"));
        assertFalse(RuntimePlatform.detect("Plan9", "mips").isKnown());
        assertEquals(RuntimePlatform.LINUX_X86_64, RuntimePlatform.parse("linux-x86_64"));
        assertEquals("windows-x86_64", RuntimePlatform.WINDOWS_X86_64.id());
        assertTrue(RuntimePlatform.LINUX_X86_64.accepts(RuntimePlatform.ANY));
        assertFalse(RuntimePlatform.LINUX_X86_64.accepts(RuntimePlatform.WINDOWS_X86_64));
    }

    @Test
    void globMatching() {
        assertTrue(RuntimeArtifact.Glob.matches("bin/*", "bin/ffmpeg"));
        assertFalse(RuntimeArtifact.Glob.matches("bin/*", "bin/sub/ffmpeg"));
        assertTrue(RuntimeArtifact.Glob.matches("**/*.so", "lib/a/b/libx.so"));
        assertTrue(RuntimeArtifact.Glob.matches("**/*.so", "libx.so"));
        assertTrue(RuntimeArtifact.Glob.matches("*.so.*", "libvulkan.so.1"));
        assertFalse(RuntimeArtifact.Glob.matches("*.so", "libvulkan.so.1"));
    }
}
