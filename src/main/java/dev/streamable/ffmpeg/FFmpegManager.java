package dev.streamable.ffmpeg;

import dev.streamable.StreamAbleLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Finds and describes the FFmpeg binary, once, for the whole mod.
 *
 * <p>Recording, streaming, remuxing and probing all go through this class so
 * there is exactly one binary in play. In particular it looks in Record-able's
 * old bundle directory before considering a download, so a player upgrading
 * from Record-able does not fetch a second copy of FFmpeg.</p>
 */
public final class FFmpegManager {

    private static final long PROBE_TIMEOUT_SECONDS = 15;

    private final Path gameDirectory;
    private volatile Resolution resolution;
    private volatile FFmpegRuntime managed;
    private volatile String configuredPath = "";

    public FFmpegManager(Path gameDirectory) {
        this.gameDirectory = gameDirectory;
    }

    /** Where a resolved binary came from. */
    public enum Origin { CONFIGURED, MANAGED, MANAGED_PREVIOUS, STREAM_ABLE_BUNDLE, RECORD_ABLE_BUNDLE, SYSTEM_PATH, NONE }

    /**
     * @param executable absolute path or bare command name; empty when not found
     * @param origin     where it was found
     * @param version    reported version string, or a blank string
     */
    public record Resolution(String executable, Origin origin, String version) {

        public boolean isAvailable() {
            return !executable.isEmpty();
        }

        public String describe() {
            return switch (origin) {
                case CONFIGURED -> "Expert override path";
                case MANAGED -> "Managed by Stream-able (verified)";
                case MANAGED_PREVIOUS -> "Previous Stream-able runtime (update pending)";
                case STREAM_ABLE_BUNDLE -> "Legacy Stream-able download";
                case RECORD_ABLE_BUNDLE -> "Reused from an existing Record-able install";
                case SYSTEM_PATH -> "Found on PATH";
                case NONE -> "Not found";
            };
        }
    }

    /**
     * Directory Stream-able downloads FFmpeg into.
     *
     * <p>The {@code bin} segment matches what {@link FfmpegBundleManager} writes,
     * so both classes agree on where the binary lives.</p>
     */
    public Path bundleDirectory() {
        return gameDirectory.resolve("stream-able").resolve("ffmpeg").resolve("bin");
    }

    /**
     * Record-able's bundle directory.
     *
     * <p>Checked deliberately: a player upgrading from Record-able already has a
     * verified FFmpeg build on disk, and downloading a second copy of it would
     * be pure waste.</p>
     */
    public Path legacyBundleDirectory() {
        return gameDirectory.resolve("recordable").resolve("ffmpeg").resolve("bin");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static String executableName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "ffmpeg.exe" : "ffmpeg";
    }

    /** Connects the verified managed runtime; it is preferred over everything but an override. */
    public void setManagedRuntime(FFmpegRuntime runtime) {
        this.managed = runtime;
    }

    public FFmpegRuntime managedRuntime() {
        return managed;
    }

    /** The expert override path; blank means "no override". */
    public void setConfiguredPath(String path) {
        this.configuredPath = path == null ? "" : path.trim();
    }

    /** Cached resolution, resolving on first use. */
    public Resolution resolution() {
        Resolution current = resolution;
        if (current == null) {
            synchronized (this) {
                current = resolution;
                if (current == null) {
                    current = resolve(configuredPath);
                    resolution = current;
                }
            }
        }
        return current;
    }

    /** Forces a fresh lookup, e.g. after a download or a settings change. */
    public Resolution refresh(String configuredPath) {
        setConfiguredPath(configuredPath);
        Resolution fresh = resolve(this.configuredPath);
        resolution = fresh;
        return fresh;
    }

    private Resolution resolve(String configuredPath) {
        List<java.util.Map.Entry<Path, Origin>> candidates = new ArrayList<>();
        if (configuredPath != null && !configuredPath.isBlank()) {
            candidates.add(java.util.Map.entry(Path.of(configuredPath.trim()), Origin.CONFIGURED));
        }
        FFmpegRuntime runtime = managed;
        if (runtime != null) {
            runtime.installedExecutable().ifPresent(path ->
                    candidates.add(java.util.Map.entry(path, Origin.MANAGED)));
            for (Path previous : runtime.previousExecutables()) {
                candidates.add(java.util.Map.entry(previous, Origin.MANAGED_PREVIOUS));
            }
        }
        candidates.add(java.util.Map.entry(bundleDirectory().resolve(executableName()),
                Origin.STREAM_ABLE_BUNDLE));
        candidates.add(java.util.Map.entry(legacyBundleDirectory().resolve(executableName()),
                Origin.RECORD_ABLE_BUNDLE));

        for (var candidate : candidates) {
            Path path = candidate.getKey();
            if (Files.isRegularFile(path) && (Files.isExecutable(path) || isWindows())) {
                String version = queryVersion(path.toAbsolutePath().toString());
                if (version != null) {
                    if (candidate.getValue() == Origin.RECORD_ABLE_BUNDLE) {
                        StreamAbleLog.FFMPEG.info(
                                "Reusing the FFmpeg binary already downloaded by Record-able.");
                    }
                    return new Resolution(path.toAbsolutePath().toString(), candidate.getValue(), version);
                }
            }
        }

        String version = queryVersion("ffmpeg");
        if (version != null) {
            return new Resolution("ffmpeg", Origin.SYSTEM_PATH, version);
        }
        return new Resolution("", Origin.NONE, "");
    }

    /** Runs {@code ffmpeg -version}; {@code null} when the binary does not work. */
    private static String queryVersion(String executable) {
        try {
            Process process = FFmpegProcesses.builder(List.of(executable, "-hide_banner", "-version"))
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            String firstLine = output.lines().findFirst().orElse("").trim();
            return firstLine.isEmpty() ? "ffmpeg" : firstLine;
        } catch (IOException e) {
            return null;                    // not present: an expected outcome, not an error
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public boolean isAvailable() {
        return resolution().isAvailable();
    }

    /** The executable to invoke; empty string when unavailable. */
    public String executable() {
        return resolution().executable();
    }

    // ---- shared instance ---------------------------------------------------
    // Classes carried over from Record-able look FFmpeg up statically. Rather
    // than let them each re-detect it, they share this one instance.

    private static volatile FFmpegManager shared;

    /** Installs the process-wide instance. Called once during mod init. */
    public static void initShared(FFmpegManager manager) {
        shared = manager;
    }

    /** The shared instance, falling back to the working directory if not yet set. */
    public static FFmpegManager shared() {
        FFmpegManager current = shared;
        if (current == null) {
            synchronized (FFmpegManager.class) {
                current = shared;
                if (current == null) {
                    current = new FFmpegManager(Path.of("."));
                    shared = current;
                }
            }
        }
        return current;
    }

    /** Convenience detection result used by the ported recording helpers. */
    public record Detection(boolean found, String executable, String version) {
    }

    /** Detects FFmpeg through the shared manager. */
    public static Detection detect() {
        Resolution resolution = shared().resolution();
        return new Detection(resolution.isAvailable(), resolution.executable(), resolution.version());
    }
}
