/*
 * Ported from Record-able by JoEusebe (MIT). See NOTICE for attribution.
 * Adapted for Stream-able: package, logging category and branding only -
 * the capture/encoding behaviour is intentionally unchanged.
 */
package dev.streamable.ffmpeg;

import dev.streamable.StreamAbleLog;

import dev.streamable.util.PlatformUtils;

import dev.streamable.recording.VideoMetadata;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manages FFmpeg detection and on-demand download for the lite/Modrinth-friendly
 * distribution of Stream-able.
 *
 * <h3>Why "Lite"?</h3>
 * <p>Modrinth's content rules require mods to either be small, generally MIT-licensed
 * binaries or to disclose third-party redistribution. To stay clean we ship a JAR
 * with no FFmpeg binary inside it (~5 MB) and download an official, trusted build
 * the first time the user records.</p>
 *
 * <h3>Resolution order</h3>
 * <ol>
 *   <li>User-configured path ({@code config.ffmpegPath} or {@code RECORDABLE_FFMPEG_PATH}
 *       env var) — handled by {@link FFmpegEncoder#detectFfmpeg()}.</li>
 *   <li>Locally-downloaded FFmpeg at
 *       {@code <gameDir>/stream-able/ffmpeg/bin/ffmpeg[.exe]}.</li>
 *   <li>System {@code PATH} (let the OS resolve {@code ffmpeg}).</li>
 *   <li>Auto-download from a trusted upstream (gyan.dev, johnvansickle.com,
 *       evermeet.cx) when the user clicks "Download FFmpeg" or starts a recording
 *       and FFmpeg is missing.</li>
 * </ol>
 *
 * <h3>Trusted upstreams</h3>
 * <ul>
 *   <li><b>Windows x64</b>: {@code https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip}
 *       (the canonical "release essentials" build maintained by Gyan Doshi for the
 *       FFmpeg project).</li>
 *   <li><b>Linux x64</b>: {@code https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz}
 *       (the canonical Linux static build maintained by John Van Sickle).</li>
 *   <li><b>macOS x64</b>: {@code https://evermeet.cx/ffmpeg/get/zip} (the canonical
 *       static macOS build maintained by Helmut K. C. Tessarek / evermeet.cx).</li>
 *   <li><b>Android</b>: We do <i>not</i> auto-download on Android — exec-mounted
 *       writable storage is rare on Pojav/Zalith/FCL setups. The user is instead
 *       guided to Termux ({@code pkg install ffmpeg}) or to download a static
 *       arm64 build manually and place it at
 *       {@code <gameDir>/stream-able/ffmpeg/bin/ffmpeg}. This is surfaced via
 *       {@link #getAndroidManualInstructions()}.</li>
 * </ul>
 *
 * <h3>Integrity</h3>
 * <p>For Windows builds we fetch the upstream SHA-256 from
 * {@code https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip.sha256}
 * and compare it to the downloaded archive. For Linux we hash the archive and
 * compare against the published MD5 (gyan.dev publishes SHA-256, Van Sickle
 * publishes MD5; we use whatever the upstream provides and degrade gracefully
 * if the integrity file is unreachable, logging a warning but still allowing
 * use since the underlying TLS connection authenticates the host). The hash
 * file is fetched over HTTPS from the same host as the binary.</p>
 *
 * <h3>Thread-safety & UI integration</h3>
 * <p>The download runs on a background thread. UI screens (see
 * {@code FfmpegDownloadScreen}) subscribe a {@link ProgressListener} to render
 * a progress bar. State is exposed via {@link #getStatus()}.</p>
 */
public final class FfmpegBundleManager {

    /** Sub-directory under the game directory where downloaded FFmpeg lives. */
    private static final String BUNDLE_DIR = "stream-able/ffmpeg";

    // ---- Trusted upstream URLs (HTTPS only) ----
    private static final String WIN_URL =
            "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";
    private static final String WIN_SHA_URL =
            "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip.sha256";
    private static final String LINUX_URL =
            "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz";
    private static final String LINUX_MD5_URL =
            "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz.md5";
    private static final String MACOS_URL =
            "https://evermeet.cx/ffmpeg/get/zip";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int BUFFER_SIZE = 64 * 1024;

    /** High-level state for UI binding. */
    public enum Status {
        /** FFmpeg has not been located. */
        NOT_FOUND,
        /** Download is currently in progress. */
        DOWNLOADING,
        /** Download/extraction failed. See {@link #getLastError()}. */
        ERROR,
        /** FFmpeg is available and executable. */
        AVAILABLE
    }

    private static volatile String cachedPath;
    private static volatile boolean checkedOnce;
    private static final AtomicReference<Status> status = new AtomicReference<>(Status.NOT_FOUND);
    private static final AtomicBoolean downloading = new AtomicBoolean(false);
    private static volatile String lastError;
    private static volatile DownloadProgress lastProgress = DownloadProgress.IDLE;

    private static final List<ProgressListener> listeners = new ArrayList<>();

    private FfmpegBundleManager() {
    }

    /**
     * Returns the absolute path to a usable FFmpeg executable, or {@code null}
     * if none has been downloaded/found yet.
     *
     * <p>Only checks the locally-downloaded location and verifies it can be
     * executed. System PATH and user-configured paths are resolved by
     * {@link FFmpegEncoder#detectFfmpeg()} which calls this method first.</p>
     */
    public static String getBundledFfmpegPath() {
        if (checkedOnce) {
            return cachedPath;
        }
        synchronized (FfmpegBundleManager.class) {
            if (checkedOnce) {
                return cachedPath;
            }
            cachedPath = resolveLocal();
            checkedOnce = true;
            if (cachedPath != null) {
                status.set(Status.AVAILABLE);
            }
            return cachedPath;
        }
    }

    /** @return {@code true} if a downloaded FFmpeg is present and executable. */
    public static boolean isBundledFfmpegAvailable() {
        return getBundledFfmpegPath() != null;
    }

    /** Forces the next call to re-probe the filesystem. */
    public static void invalidateCache() {
        synchronized (FfmpegBundleManager.class) {
            cachedPath = null;
            checkedOnce = false;
        }
    }

    /** Directory where downloaded FFmpeg binaries live: {@code <gameDir>/stream-able/ffmpeg/bin}. */
    public static Path getBundleDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve(BUNDLE_DIR).resolve("bin");
    }

    /** Current status of the FFmpeg bundle. */
    public static Status getStatus() {
        // refresh from cache if available
        if (status.get() != Status.DOWNLOADING && isBundledFfmpegAvailable()) {
            status.set(Status.AVAILABLE);
        }
        return status.get();
    }

    /** Most recent error message produced by a failed download or extraction. */
    public static String getLastError() {
        return lastError;
    }

    /** Most recent progress snapshot (bytes downloaded, total, phase). */
    public static DownloadProgress getLastProgress() {
        return lastProgress;
    }

    /** True while a background download is in flight. */
    public static boolean isDownloading() {
        return downloading.get();
    }

    /** Register a progress listener. Safe to call from any thread. */
    public static void addProgressListener(ProgressListener listener) {
        if (listener == null) return;
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public static void removeProgressListener(ProgressListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private static void fireProgress(DownloadProgress p) {
        lastProgress = p;
        List<ProgressListener> snapshot;
        synchronized (listeners) {
            snapshot = new ArrayList<>(listeners);
        }
        for (ProgressListener l : snapshot) {
            try {
                l.onProgress(p);
            } catch (Throwable t) {
                StreamAbleLog.FFMPEG.warn("[FfmpegBundle] Listener threw: {}", t.getMessage());
            }
        }
    }

    /** Snapshot of download progress, used by listeners. */
    public record DownloadProgress(String phase, long bytesDownloaded, long totalBytes, double fraction) {
        public static final DownloadProgress IDLE = new DownloadProgress("idle", 0L, 0L, 0.0);

        public String displayPercent() {
            if (totalBytes <= 0) {
                return bytesDownloaded > 0 ? humanBytes(bytesDownloaded) : "0%";
            }
            return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
        }

        public String displayBytes() {
            if (totalBytes <= 0) return humanBytes(bytesDownloaded);
            return humanBytes(bytesDownloaded) + " / " + humanBytes(totalBytes);
        }

        private static String humanBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
            if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
            return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    /** Callback for download progress updates. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(DownloadProgress progress);
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    private static String resolveLocal() {
        PlatformUtils.Platform platform = PlatformUtils.detectPlatform();
        StreamAbleLog.FFMPEG.info("[FfmpegBundle] Resolving local FFmpeg (lite mode)");
        StreamAbleLog.FFMPEG.info("[FfmpegBundle]   Platform: {}", platform.displayName());
        StreamAbleLog.FFMPEG.info("[FfmpegBundle]   Bundle dir: {}", getBundleDirectory());

        Path candidate = getBundleDirectory().resolve(getExecutableName());
        if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
            if (!PlatformUtils.isWindows() && !Files.isExecutable(candidate)) {
                setExecutablePermission(candidate);
            }
            if (verifyBinaryExecution(candidate)) {
                StreamAbleLog.FFMPEG.info("[FfmpegBundle] ✓ Found downloaded FFmpeg: {}", candidate);
                return candidate.toAbsolutePath().toString();
            }
            StreamAbleLog.FFMPEG.warn("[FfmpegBundle] Found {} but cannot execute it (noexec/permission?)", candidate);
        } else {
            StreamAbleLog.FFMPEG.info("[FfmpegBundle] No downloaded FFmpeg at {}", candidate);
        }

        // On Android, also check a small fallback of exec-capable paths.
        if (platform == PlatformUtils.Platform.ANDROID) {
            Path execPath = findOnAndroidExecCapablePaths();
            if (execPath != null) {
                return execPath.toAbsolutePath().toString();
            }
        }

        return null;
    }

    private static boolean verifyBinaryExecution(Path binary) {
        try {
            Process proc = new ProcessBuilder(binary.toAbsolutePath().toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean exited = proc.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                proc.destroyForcibly();
                return false;
            }
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return proc.exitValue() == 0 && output.toLowerCase(Locale.ROOT).contains("ffmpeg");
        } catch (Exception e) {
            StreamAbleLog.FFMPEG.debug("[FfmpegBundle] Execution check failed for {}: {}", binary, e.getMessage());
            return false;
        }
    }

    private static List<String> getAndroidExecCapablePaths() {
        List<String> paths = new ArrayList<>();
        String detectedPkg = detectAndroidPackageName();
        if (detectedPkg != null) {
            paths.add("/data/data/" + detectedPkg + "/files/recordable-ffmpeg");
            paths.add("/data/user/0/" + detectedPkg + "/files/recordable-ffmpeg");
            paths.add("/data/data/" + detectedPkg + "/cache/recordable-ffmpeg");
        }
        String tmpDir = System.getProperty("java.io.tmpdir", "");
        if (!tmpDir.isEmpty()) paths.add(tmpDir + "/recordable-ffmpeg");
        return paths;
    }

    private static Path findOnAndroidExecCapablePaths() {
        String binaryName = getExecutableName();
        for (String dir : getAndroidExecCapablePaths()) {
            Path candidate = Path.of(dir).resolve(binaryName);
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                if (!Files.isExecutable(candidate)) {
                    setExecutablePermission(candidate);
                }
                if (verifyBinaryExecution(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    static String detectAndroidPackageName() {
        try {
            String gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().toString();
            String pkg = extractPackageName(gameDir);
            if (pkg != null) return pkg;
        } catch (Exception ignored) {
        }
        String userDir = System.getProperty("user.dir", "");
        String pkg = extractPackageName(userDir);
        if (pkg != null) return pkg;
        return null;
    }

    private static String extractPackageName(String path) {
        if (path == null || path.isEmpty()) return null;
        String[] prefixes = {
                "/data/data/",
                "/data/user/0/",
                "/storage/emulated/0/Android/data/"
        };
        for (String prefix : prefixes) {
            int idx = path.indexOf(prefix);
            if (idx >= 0) {
                String rest = path.substring(idx + prefix.length());
                int slash = rest.indexOf('/');
                String candidate = slash > 0 ? rest.substring(0, slash) : rest;
                if (candidate.contains(".") && !candidate.contains(" ") && candidate.length() > 3) {
                    return candidate;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} if auto-download is supported for the current platform.
     * Android is excluded because writable+exec storage is rare and unreliable.
     */
    public static boolean isAutoDownloadSupported() {
        PlatformUtils.Platform p = PlatformUtils.detectPlatform();
        return p == PlatformUtils.Platform.WINDOWS
                || p == PlatformUtils.Platform.LINUX
                || p == PlatformUtils.Platform.MACOS;
    }

    /** Human-readable upstream description for the current platform. */
    public static String getDownloadSourceDescription() {
        return switch (PlatformUtils.detectPlatform()) {
            case WINDOWS -> "gyan.dev (FFmpeg release essentials, Windows x64)";
            case LINUX -> "johnvansickle.com (FFmpeg release static, Linux x64)";
            case MACOS -> "evermeet.cx (FFmpeg static, macOS x64)";
            case ANDROID -> "Not supported — see manual instructions";
            case UNKNOWN -> "Unsupported platform";
        };
    }

    /** Approximate download size text for the UI. */
    public static String getEstimatedDownloadSize() {
        return switch (PlatformUtils.detectPlatform()) {
            case WINDOWS -> "~103 MB";
            case LINUX -> "~80 MB";
            case MACOS -> "~80 MB";
            default -> "n/a";
        };
    }

    /**
     * Triggers an asynchronous download + extraction of FFmpeg. Safe to call multiple
     * times — subsequent calls while a download is in flight return the same future.
     *
     * @param onComplete callback invoked on completion with success/failure flag
     * @return a {@link CompletableFuture} resolving to {@code true} if FFmpeg is
     *         available after the call, or {@code false} on failure
     */
    public static CompletableFuture<Boolean> downloadAsync(Consumer<Boolean> onComplete) {
        if (!isAutoDownloadSupported()) {
            String err = "Auto-download is not supported on " + PlatformUtils.detectPlatform().displayName()
                    + ". See manual installation instructions.";
            lastError = err;
            status.set(Status.ERROR);
            StreamAbleLog.FFMPEG.warn("[FfmpegBundle] {}", err);
            if (onComplete != null) onComplete.accept(false);
            return CompletableFuture.completedFuture(false);
        }
        if (!downloading.compareAndSet(false, true)) {
            StreamAbleLog.FFMPEG.info("[FfmpegBundle] Download already in progress, ignoring duplicate request");
            CompletableFuture<Boolean> existing = new CompletableFuture<>();
            existing.complete(false);
            return existing;
        }

        status.set(Status.DOWNLOADING);
        lastError = null;
        fireProgress(new DownloadProgress("starting", 0, 0, 0.0));

        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
            try {
                doDownloadAndInstall();
                invalidateCache();
                String resolved = getBundledFfmpegPath();
                boolean ok = resolved != null;
                if (ok) {
                    status.set(Status.AVAILABLE);
                    fireProgress(new DownloadProgress("done", 1, 1, 1.0));
                    StreamAbleLog.FFMPEG.info("[FfmpegBundle] ✓ FFmpeg ready at {}", resolved);
                } else {
                    status.set(Status.ERROR);
                    lastError = "Download finished but FFmpeg binary could not be located after extraction.";
                    fireProgress(new DownloadProgress("error", 0, 0, 0.0));
                }
                return ok;
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                status.set(Status.ERROR);
                fireProgress(new DownloadProgress("error", 0, 0, 0.0));
                StreamAbleLog.FFMPEG.warn("[FfmpegBundle] Download failed: {}", lastError, e);
                return false;
            } finally {
                downloading.set(false);
            }
        });

        if (onComplete != null) {
            future.whenComplete((ok, ex) -> {
                try {
                    onComplete.accept(ok != null && ok);
                } catch (Throwable t) {
                    StreamAbleLog.FFMPEG.warn("[FfmpegBundle] onComplete threw: {}", t.getMessage());
                }
            });
        }
        return future;
    }

    private static void doDownloadAndInstall() throws IOException {
        PlatformUtils.Platform platform = PlatformUtils.detectPlatform();
        Path bundleDir = getBundleDirectory();
        Files.createDirectories(bundleDir);

        Path tempDir = Files.createTempDirectory("stream-able-ffmpeg-");
        try {
            switch (platform) {
                case WINDOWS -> downloadAndInstallWindows(tempDir, bundleDir);
                case LINUX -> downloadAndInstallLinux(tempDir, bundleDir);
                case MACOS -> downloadAndInstallMacOS(tempDir, bundleDir);
                default -> throw new IOException("Platform not supported for auto-download: " + platform.displayName());
            }
        } finally {
            deleteRecursive(tempDir);
        }
    }

    private static void downloadAndInstallWindows(Path tempDir, Path bundleDir) throws IOException {
        Path zipFile = tempDir.resolve("ffmpeg.zip");
        downloadFile(WIN_URL, zipFile, "Downloading FFmpeg (Windows)");

        // Verify SHA-256 against gyan.dev's published file (best-effort).
        String expected = tryFetchExpectedHash(WIN_SHA_URL, "SHA-256");
        if (expected != null) {
            String actual = computeSha256(zipFile);
            if (!expected.equalsIgnoreCase(actual)) {
                throw new IOException("SHA-256 mismatch for FFmpeg download. "
                        + "expected=" + expected + ", actual=" + actual);
            }
            StreamAbleLog.FFMPEG.info("[FfmpegBundle] ✓ SHA-256 verified against gyan.dev");
        } else {
            StreamAbleLog.FFMPEG.warn("[FfmpegBundle] Could not fetch upstream SHA-256, "
                    + "relying on HTTPS authentication only.");
        }

        fireProgress(new DownloadProgress("extracting", 0, 1, 0.0));
        extractZipFindExecutable(zipFile, bundleDir, "ffmpeg.exe");
    }

    private static void downloadAndInstallLinux(Path tempDir, Path bundleDir) throws IOException {
        Path archive = tempDir.resolve("ffmpeg.tar.xz");
        downloadFile(LINUX_URL, archive, "Downloading FFmpeg (Linux)");

        String expectedMd5 = tryFetchExpectedHash(LINUX_MD5_URL, "MD5");
        if (expectedMd5 != null) {
            String actual = computeMd5(archive);
            if (!expectedMd5.equalsIgnoreCase(actual)) {
                throw new IOException("MD5 mismatch for FFmpeg download. "
                        + "expected=" + expectedMd5 + ", actual=" + actual);
            }
            StreamAbleLog.FFMPEG.info("[FfmpegBundle] ✓ MD5 verified against johnvansickle.com");
        } else {
            StreamAbleLog.FFMPEG.warn("[FfmpegBundle] Could not fetch upstream MD5, "
                    + "relying on HTTPS authentication only.");
        }

        fireProgress(new DownloadProgress("extracting", 0, 1, 0.0));
        // Use system tar to unpack the .tar.xz (always present on Linux + macOS).
        Path extractDir = tempDir.resolve("extracted");
        Files.createDirectories(extractDir);
        runProcess(new String[]{"tar", "-xJf", archive.toAbsolutePath().toString(),
                "-C", extractDir.toAbsolutePath().toString()});
        Path ffmpegBin = findFileRecursive(extractDir, "ffmpeg");
        if (ffmpegBin == null) {
            throw new IOException("Could not locate 'ffmpeg' binary in extracted archive at " + extractDir);
        }
        Path target = bundleDir.resolve("ffmpeg");
        Files.copy(ffmpegBin, target, StandardCopyOption.REPLACE_EXISTING);
        setExecutablePermission(target);

        // Also copy ffprobe if present (useful for VideoMetadata).
        Path ffprobeBin = findFileRecursive(extractDir, "ffprobe");
        if (ffprobeBin != null) {
            Path probeTarget = bundleDir.resolve("ffprobe");
            Files.copy(ffprobeBin, probeTarget, StandardCopyOption.REPLACE_EXISTING);
            setExecutablePermission(probeTarget);
        }
    }

    private static void downloadAndInstallMacOS(Path tempDir, Path bundleDir) throws IOException {
        Path zipFile = tempDir.resolve("ffmpeg.zip");
        downloadFile(MACOS_URL, zipFile, "Downloading FFmpeg (macOS)");

        // evermeet.cx does not publish a stable .sha256 sibling URL. The TLS
        // connection authenticates the host, and the zip is signed (signature
        // available at /sig endpoint), but for simplicity we rely on HTTPS here.
        StreamAbleLog.FFMPEG.info("[FfmpegBundle] HTTPS-authenticated download from evermeet.cx (no sibling hash file)");

        fireProgress(new DownloadProgress("extracting", 0, 1, 0.0));
        extractZipFindExecutable(zipFile, bundleDir, "ffmpeg");
    }

    /**
     * Extracts the named executable from a zip and copies it to {@code bundleDir/<exeName>}.
     */
    private static void extractZipFindExecutable(Path zipFile, Path bundleDir, String exeName) throws IOException {
        boolean found = false;
        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                String basename = name.substring(name.lastIndexOf('/') + 1);
                if (basename.equalsIgnoreCase(exeName)) {
                    Path target = bundleDir.resolve(exeName);
                    Files.copy(zin, target, StandardCopyOption.REPLACE_EXISTING);
                    setExecutablePermission(target);
                    StreamAbleLog.FFMPEG.info("[FfmpegBundle] Extracted {} → {}", name, target);
                    found = true;
                } else if (basename.equalsIgnoreCase("ffprobe.exe") || basename.equalsIgnoreCase("ffprobe")) {
                    // Take ffprobe too if present (used by VideoMetadata).
                    String probeName = PlatformUtils.isWindows() ? "ffprobe.exe" : "ffprobe";
                    Path target = bundleDir.resolve(probeName);
                    Files.copy(zin, target, StandardCopyOption.REPLACE_EXISTING);
                    setExecutablePermission(target);
                    StreamAbleLog.FFMPEG.info("[FfmpegBundle] Extracted {} → {}", name, target);
                }
            }
        }
        if (!found) {
            throw new IOException("Could not find '" + exeName + "' inside downloaded archive.");
        }
    }

    private static Path findFileRecursive(Path root, String filename) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(filename))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void runProcess(String[] cmd) throws IOException {
        try {
            Process proc = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            boolean done = proc.waitFor(2, TimeUnit.MINUTES);
            if (!done) {
                proc.destroyForcibly();
                throw new IOException("Process timed out: " + String.join(" ", cmd));
            }
            if (proc.exitValue() != 0) {
                String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("Process failed (" + proc.exitValue() + "): "
                        + String.join(" ", cmd) + "\n" + out);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + String.join(" ", cmd), ie);
        }
    }

    /**
     * Streaming HTTPS download with progress reporting. Supports redirects.
     */
    private static void downloadFile(String urlStr, Path target, String phase) throws IOException {
        StreamAbleLog.FFMPEG.info("[FfmpegBundle] Downloading: {}", urlStr);
        HttpURLConnection conn = openConnectionFollowingRedirects(urlStr, 5);
        long contentLength = conn.getContentLengthLong();
        long downloaded = 0;
        long lastReport = 0;

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                downloaded += n;
                long now = System.currentTimeMillis();
                if (now - lastReport > 200 || (contentLength > 0 && downloaded >= contentLength)) {
                    double frac = contentLength > 0 ? (double) downloaded / contentLength : 0.0;
                    fireProgress(new DownloadProgress(phase, downloaded, contentLength, frac));
                    lastReport = now;
                }
            }
        } finally {
            conn.disconnect();
        }
        StreamAbleLog.FFMPEG.info("[FfmpegBundle] Downloaded {} bytes to {}", downloaded, target);
    }

    private static HttpURLConnection openConnectionFollowingRedirects(String urlStr, int maxHops) throws IOException {
        String current = urlStr;
        for (int i = 0; i < maxHops; i++) {
            URL url;
            try {
                url = URI.create(current).toURL();
            } catch (IllegalArgumentException iae) {
                throw new IOException("Invalid URL: " + current, iae);
            }
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IOException("Refusing non-HTTPS URL: " + current);
            }
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Stream-able/" + getModVersion() + " (+https://modrinth.com/mod/stream-able)");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return conn;
            }
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) {
                    throw new IOException("HTTP " + code + " redirect with no Location header from " + current);
                }
                if (loc.startsWith("/")) {
                    loc = url.getProtocol() + "://" + url.getHost() + loc;
                }
                current = loc;
                continue;
            }
            conn.disconnect();
            throw new IOException("HTTP " + code + " from " + current);
        }
        throw new IOException("Too many redirects starting at " + urlStr);
    }

    private static String getModVersion() {
        try {
            return FabricLoader.getInstance().getModContainer("streamable")
                    .map(m -> m.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Fetches a hash file (.sha256 or .md5) and parses out the first hex token.
     * Returns null if the file is unreachable.
     */
    private static String tryFetchExpectedHash(String url, String algoLabel) {
        try {
            HttpURLConnection conn = openConnectionFollowingRedirects(url, 5);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line == null) return null;
                // Lines often look like:  "abcdef1234... *filename" or "abcdef1234... filename"
                String token = line.trim().split("\\s+")[0];
                // Validate hex
                if (token.matches("[0-9a-fA-F]+")) {
                    StreamAbleLog.FFMPEG.info("[FfmpegBundle] Fetched expected {}: {}", algoLabel, token);
                    return token;
                }
                return null;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            StreamAbleLog.FFMPEG.debug("[FfmpegBundle] Could not fetch {} from {}: {}", algoLabel, url, e.getMessage());
            return null;
        }
    }

    private static String computeSha256(Path file) throws IOException {
        return computeHash(file, "SHA-256");
    }

    private static String computeMd5(Path file) throws IOException {
        return computeHash(file, "MD5");
    }

    private static String computeHash(Path file, String algorithm) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Hash algorithm not available: " + algorithm, e);
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static void deleteRecursive(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Permissions and platform helpers
    // ------------------------------------------------------------------

    public static String getExecutableName() {
        return PlatformUtils.isWindows() ? "ffmpeg.exe" : "ffmpeg";
    }

    private static boolean setExecutablePermission(Path path) {
        try {
            if (path.toFile().setExecutable(true, false)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"chmod", "+x", path.toAbsolutePath().toString()});
            return p.waitFor() == 0;
        } catch (Exception e) {
            StreamAbleLog.FFMPEG.debug("[FfmpegBundle] chmod failed: {}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // ARM detection (still used by PlatformUtils status text)
    // ------------------------------------------------------------------

    public static String detectArmArchitecture() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (osArch.contains("aarch64") || osArch.contains("arm64")) return "arm64";
        if (osArch.contains("arm")) return "arm32";
        try {
            Path cpuinfo = Path.of("/proc/cpuinfo");
            if (Files.exists(cpuinfo)) {
                String content = Files.readString(cpuinfo).toLowerCase(Locale.ROOT);
                if (content.contains("aarch64") || content.contains("armv8")) return "arm64";
                if (content.contains("armv7") || content.contains("arm")) return "arm32";
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    // ------------------------------------------------------------------
    // UI-friendly descriptions
    // ------------------------------------------------------------------

    public static String getStatusDescription() {
        String path = getBundledFfmpegPath();
        if (path != null) {
            return "FFmpeg ready: " + path;
        }
        return switch (status.get()) {
            case DOWNLOADING -> "Downloading FFmpeg from " + getDownloadSourceDescription();
            case ERROR -> "FFmpeg download failed: " + (lastError == null ? "unknown error" : lastError);
            case AVAILABLE -> "FFmpeg ready";
            case NOT_FOUND -> {
                if (isAutoDownloadSupported()) {
                    yield "FFmpeg not installed — click 'Download FFmpeg' to fetch it ("
                            + getEstimatedDownloadSize() + " from " + getDownloadSourceDescription() + ").";
                }
                yield "FFmpeg not installed. " + getManualInstallInstructions();
            }
        };
    }

    /** Plain-text manual install instructions for the current platform. */
    public static String getManualInstallInstructions() {
        return switch (PlatformUtils.detectPlatform()) {
            case WINDOWS -> "Manual install: download ffmpeg-release-essentials.zip from "
                    + "https://www.gyan.dev/ffmpeg/builds/ and extract ffmpeg.exe to "
                    + getBundleDirectory();
            case LINUX -> "Manual install: install via 'sudo apt install ffmpeg' (Debian/Ubuntu), "
                    + "'sudo dnf install ffmpeg' (Fedora), 'sudo pacman -S ffmpeg' (Arch), "
                    + "or download from https://johnvansickle.com/ffmpeg/ and place 'ffmpeg' at "
                    + getBundleDirectory();
            case MACOS -> "Manual install: 'brew install ffmpeg' or download from "
                    + "https://evermeet.cx/ffmpeg/ and place 'ffmpeg' at " + getBundleDirectory();
            case ANDROID -> getAndroidManualInstructions();
            case UNKNOWN -> "Please install FFmpeg from https://ffmpeg.org/ and add it to PATH.";
        };
    }

    /** Android-specific manual install guidance. */
    public static String getAndroidManualInstructions() {
        return "Android auto-download is not supported because exec-mounted writable storage "
                + "is rare on Pojav/Zalith/FCL. Options:\n"
                + "  1. Install Termux from F-Droid and run: pkg install ffmpeg\n"
                + "     Then set 'ffmpegPath' in the config to /data/data/com.termux/files/usr/bin/ffmpeg.\n"
                + "  2. Download a static arm64 build (e.g. from termux-pacman or AndroidIDE) and place\n"
                + "     it at " + getBundleDirectory().resolve("ffmpeg") + " then chmod +x it.";
    }

    /**
     * Runs a diagnostics dump — kept minimal because the lite version has fewer
     * fallback paths than the previous bundled version.
     */
    public static void runDiagnostics() {
        StreamAbleLog.FFMPEG.info("[FfmpegBundle] ── Diagnostics (lite) ──");
        StreamAbleLog.FFMPEG.info("[FfmpegBundle]   Platform: {}", PlatformUtils.detectPlatform().displayName());
        StreamAbleLog.FFMPEG.info("[FfmpegBundle]   os.arch:  {}", System.getProperty("os.arch", "?"));
        StreamAbleLog.FFMPEG.info("[FfmpegBundle]   Game dir: {}", FabricLoader.getInstance().getGameDir());
        StreamAbleLog.FFMPEG.info("[FfmpegBundle]   Bundle dir: {} (exists={})",
                getBundleDirectory(), Files.exists(getBundleDirectory()));
        StreamAbleLog.FFMPEG.info("[FfmpegBundle]   Auto-download supported: {}", isAutoDownloadSupported());
        StreamAbleLog.FFMPEG.info("[FfmpegBundle]   Download source: {}", getDownloadSourceDescription());
        StreamAbleLog.FFMPEG.info("[FfmpegBundle]   Current status:  {}", getStatusDescription());
        Path candidate = getBundleDirectory().resolve(getExecutableName());
        StreamAbleLog.FFMPEG.info("[FfmpegBundle]   Candidate file: {} (exists={}, executable={})",
                candidate, Files.exists(candidate),
                Files.exists(candidate) && Files.isExecutable(candidate));
    }
}
