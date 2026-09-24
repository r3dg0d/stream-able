/*
 * Ported from Record-able (MIT, Copyright (c) 2026 Minewind's Jo Eusebe).
 * Stream-able 1.1 replaced the original unpinned "latest build" downloader with
 * the verified runtime manager (dev.streamable.runtime / FFmpegRuntime); this
 * class now only keeps the small API the other ported helpers still call.
 */
package dev.streamable.ffmpeg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Compatibility shim for code carried over from Record-able.
 *
 * <p>Downloading is no longer done here. FFmpeg is installed by
 * {@link FFmpegRuntime}, which pins an exact build and verifies its SHA-256
 * before anything is executed; these helpers only report where the binary that
 * {@link FFmpegManager} resolved actually lives.</p>
 */
public final class FfmpegBundleManager {

    private FfmpegBundleManager() {
    }

    /** Directory holding the resolved ffmpeg (and ffprobe), or {@code null}. */
    public static Path getBundleDirectory() {
        FFmpegManager.Resolution resolution = FFmpegManager.shared().resolution();
        if (!resolution.isAvailable()) {
            return null;
        }
        Path executable = Path.of(resolution.executable());
        Path parent = executable.toAbsolutePath().getParent();
        return executable.getNameCount() > 1 && parent != null ? parent : null;
    }

    /** Whether a managed or explicitly configured FFmpeg (not one found on PATH) is in use. */
    public static boolean isBundledFfmpegAvailable() {
        FFmpegManager.Resolution resolution = FFmpegManager.shared().resolution();
        return resolution.isAvailable() && resolution.origin() != FFmpegManager.Origin.SYSTEM_PATH;
    }

    /** ARM flavour, used by the ported platform status text. */
    public static String detectArmArchitecture() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            return "arm64";
        }
        if (osArch.contains("arm")) {
            return "arm32";
        }
        try {
            Path cpuinfo = Path.of("/proc/cpuinfo");
            if (Files.exists(cpuinfo)) {
                String content = Files.readString(cpuinfo).toLowerCase(Locale.ROOT);
                if (content.contains("aarch64") || content.contains("armv8")) {
                    return "arm64";
                }
                if (content.contains("armv7") || content.contains("arm")) {
                    return "arm32";
                }
            }
        } catch (Exception ignored) {
            // Best effort only.
        }
        return "unknown";
    }
}
