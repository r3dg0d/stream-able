package dev.streamable.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedRuntimeTest {

    @TempDir
    Path root;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final FakeHttp http = new FakeHttp();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private RuntimeContext context(RuntimePlatform platform, String manifestJson) {
        return new RuntimeContext(root, platform, RuntimeManifest.parse(manifestJson),
                new RuntimeDownloader(http, 2, 1, millis -> { }), new RuntimeInstaller(), executor);
    }

    private static String manifest(String version, String sha, String url) {
        return """
                {"schema": 1, "components": {"tool": {"displayName": "Tool", "version": "%s",
                  "artifacts": [{"platform": "linux-x86_64", "urls": ["%s"], "sha256": "%s",
                                 "format": "tar.gz", "fileName": "tool.tar.gz", "stripComponents": 1,
                                 "include": ["bin/*"], "executables": ["bin/tool"]}]}}}
                """.formatted(version, url, sha);
    }

    /** A test runtime whose "initialisation" requires bin/tool to exist. */
    private static final class ToolRuntime extends ManagedRuntime {
        final List<RuntimeState> seen = new ArrayList<>();
        boolean failInit;

        ToolRuntime(RuntimeContext context) {
            super(context, context.manifest().require("tool"));
            addListener(progress -> seen.add(progress.state()));
        }

        @Override
        protected void validateInstall(Path directory) throws IOException {
            if (!Files.isRegularFile(directory.resolve("bin/tool"))) {
                throw new IOException("bin/tool missing");
            }
        }

        @Override
        protected void initialize(Path directory) throws Exception {
            if (failInit) {
                throw new IllegalStateException("native library refused to load");
            }
        }
    }

    private static byte[] toolArchive(String content) throws IOException {
        return new TestArchives.Tar().file("tool-1/bin/tool", content).file("tool-1/share/x", "doc").gzipped();
    }

    private static Path await(ManagedRuntime runtime) throws Exception {
        try {
            return runtime.ensureReady().get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("runtime did not settle", e);
        }
    }

    @Test
    void installsVerifiesAndBecomesReady() throws Exception {
        byte[] archive = toolArchive("v1");
        String url = "https://example.org/tool-1.tar.gz";
        http.serve(url, archive);
        ToolRuntime runtime = new ToolRuntime(context(RuntimePlatform.LINUX_X86_64,
                manifest("1.0", Sha256.hash(archive), url)));

        assertEquals(RuntimeState.NOT_INSTALLED, runtime.refreshFromDisk());
        Path installed = await(runtime);

        assertTrue(runtime.isReady());
        assertEquals("v1", Files.readString(installed.resolve("bin/tool")));
        assertFalse(Files.exists(installed.resolve("share/x")));
        assertTrue(InstallReceipt.read(installed).isPresent());
        assertTrue(runtime.seen.containsAll(List.of(RuntimeState.CHECKING, RuntimeState.DOWNLOADING,
                RuntimeState.VERIFYING, RuntimeState.EXTRACTING, RuntimeState.INITIALIZING, RuntimeState.READY)));
        assertFalse(Files.exists(root.resolve("downloads/tool.tar.gz")), "archive removed after install");
        try (var stream = Files.list(root.resolve("tool"))) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().startsWith(".staging")));
        }
    }

    @Test
    void secondStartUsesInstalledFilesWithoutNetwork() throws Exception {
        byte[] archive = toolArchive("v1");
        String url = "https://example.org/tool-1.tar.gz";
        http.serve(url, archive);
        RuntimeContext context = context(RuntimePlatform.LINUX_X86_64, manifest("1.0", Sha256.hash(archive), url));
        await(new ToolRuntime(context));
        int requests = http.requests.size();

        ToolRuntime again = new ToolRuntime(context);
        await(again);
        assertEquals(requests, http.requests.size());
        assertTrue(again.isReady());
    }

    @Test
    void checksumFailureLeavesNothingInstalled() {
        byte[] archive = new byte[]{1, 2, 3};
        String url = "https://example.org/tool-1.tar.gz";
        http.serve(url, archive);
        ToolRuntime runtime = new ToolRuntime(context(RuntimePlatform.LINUX_X86_64,
                manifest("1.0", "b".repeat(64), url)));
        Exception error = assertThrows(Exception.class, () -> await(runtime));
        assertEquals(RuntimeState.FAILED, runtime.state());
        assertTrue(runtime.progress().detail().contains("SHA-256"), runtime.progress().detail());
        assertFalse(Files.exists(runtime.installDirectory()));
        assertFalse(runtime.isInstalled());
    }

    @Test
    void corruptArchiveFailsCleanly() {
        byte[] archive = "definitely not gzip".getBytes();
        String url = "https://example.org/tool-1.tar.gz";
        http.serve(url, archive);
        ToolRuntime runtime = new ToolRuntime(context(RuntimePlatform.LINUX_X86_64,
                manifest("1.0", Sha256.hash(archive), url)));
        assertThrows(Exception.class, () -> await(runtime));
        assertEquals(RuntimeState.FAILED, runtime.state());
        assertFalse(Files.exists(runtime.installDirectory()));
    }

    @Test
    void initialisationFailureIsReportedAndRetryable() throws Exception {
        byte[] archive = toolArchive("v1");
        String url = "https://example.org/tool-1.tar.gz";
        http.serve(url, archive);
        ToolRuntime runtime = new ToolRuntime(context(RuntimePlatform.LINUX_X86_64,
                manifest("1.0", Sha256.hash(archive), url)));
        runtime.failInit = true;
        assertThrows(Exception.class, () -> await(runtime));
        assertEquals(RuntimeState.FAILED, runtime.state());
        assertTrue(runtime.progress().detail().contains("refused to load"));

        runtime.failInit = false;
        await(runtime);
        assertTrue(runtime.isReady());
    }

    @Test
    void upgradeReplacesThePreviousVersion() throws Exception {
        byte[] v1 = toolArchive("v1");
        byte[] v2 = toolArchive("v2");
        http.serve("https://example.org/v1.tar.gz", v1).serve("https://example.org/v2.tar.gz", v2);

        ToolRuntime old = new ToolRuntime(context(RuntimePlatform.LINUX_X86_64,
                manifest("1.0", Sha256.hash(v1), "https://example.org/v1.tar.gz")));
        Path oldDir = await(old);

        ToolRuntime upgraded = new ToolRuntime(context(RuntimePlatform.LINUX_X86_64,
                manifest("2.0", Sha256.hash(v2), "https://example.org/v2.tar.gz")));
        assertEquals(RuntimeState.UPDATE_AVAILABLE, upgraded.refreshFromDisk());
        assertEquals(List.of(oldDir), upgraded.previousInstalls());

        Path newDir = await(upgraded);
        assertEquals("v2", Files.readString(newDir.resolve("bin/tool")));
        assertFalse(Files.exists(oldDir), "superseded version cleaned up once the new one runs");
    }

    @Test
    void halfExtractedDirectoryWithoutReceiptIsNotTrusted() throws Exception {
        byte[] archive = toolArchive("good");
        String url = "https://example.org/tool-1.tar.gz";
        http.serve(url, archive);
        ToolRuntime runtime = new ToolRuntime(context(RuntimePlatform.LINUX_X86_64,
                manifest("1.0", Sha256.hash(archive), url)));
        Files.createDirectories(runtime.installDirectory().resolve("bin"));
        Files.writeString(runtime.installDirectory().resolve("bin/tool"), "crashed-midway");

        assertFalse(runtime.isInstalled());
        Path installed = await(runtime);
        assertEquals("good", Files.readString(installed.resolve("bin/tool")));
    }

    @Test
    void unsupportedPlatformNeverDownloads() {
        ToolRuntime runtime = new ToolRuntime(context(RuntimePlatform.WINDOWS_X86_64,
                manifest("1.0", "c".repeat(64), "https://example.org/x")));
        assertEquals(RuntimeState.UNSUPPORTED, runtime.state());
        assertThrows(Exception.class, () -> runtime.ensureReady().join());
        assertTrue(http.requests.isEmpty());
    }

    @Test
    void concurrentRequestsShareOneJob() throws Exception {
        byte[] archive = toolArchive("v1");
        String url = "https://example.org/tool-1.tar.gz";
        http.serve(url, archive);
        ToolRuntime runtime = new ToolRuntime(context(RuntimePlatform.LINUX_X86_64,
                manifest("1.0", Sha256.hash(archive), url)));
        var first = runtime.ensureReady();
        var second = runtime.ensureReady();
        assertTrue(first == second || second.isDone());
        first.get(20, TimeUnit.SECONDS);
        assertEquals(1, http.requests.size());
    }

    @Test
    void progressSummaryIsReadable() {
        RuntimeProgress progress = RuntimeProgress.downloading(48_234_496, 104_857_600, "");
        assertEquals("46%", progress.percentText());
        assertTrue(progress.summary().startsWith("Downloading - 46%"), progress.summary());
        assertEquals("Ready", RuntimeProgress.of(RuntimeState.READY, "").summary());
    }
}
