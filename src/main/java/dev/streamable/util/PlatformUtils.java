/*
 * Ported from Record-able by JoEusebe (MIT). See NOTICE for attribution.
 * Adapted for Stream-able: package, logging category and branding only -
 * the capture/encoding behaviour is intentionally unchanged.
 */
package dev.streamable.util;

import dev.streamable.StreamAbleLog;
import dev.streamable.ffmpeg.FFmpegManager;

import dev.streamable.ffmpeg.FfmpegBundleManager;

import dev.streamable.audio.AudioCapture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Central platform detection utility for cross-platform support.
 *
 * <p>Detects the current operating system, architecture, and available
 * system tools (FFmpeg). Provides helper methods for platform-specific behavior.</p>
 */
public final class PlatformUtils {

    /** Supported platform categories. */
    public enum Platform {
        WINDOWS("Windows"),
        LINUX("Linux"),
        MACOS("macOS"),
        ANDROID("Android"),
        UNKNOWN("Unknown");

        private final String displayName;

        Platform(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private static volatile Platform cachedPlatform;

    private PlatformUtils() {
    }

    /**
     * Detects the current platform. Result is cached after the first call.
     *
     * <p>Android detection checks for ARM/aarch64 architecture on Linux combined
     * with the presence of Android-specific paths (e.g., PojavLauncher on Android).</p>
     */
    public static Platform detectPlatform() {
        Platform cached = cachedPlatform;
        if (cached != null) {
            return cached;
        }

        Platform detected = probePlatform();
        cachedPlatform = detected;
        StreamAbleLog.CORE.info("Detected platform: {} (os.name={}, os.arch={})",
                detected.displayName(),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"));
        return detected;
    }

    private static Platform probePlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        if (isAndroidEnvironment(osName, osArch)) {
            return Platform.ANDROID;
        }

        if (osName.contains("win")) {
            return Platform.WINDOWS;
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return Platform.MACOS;
        }
        if (osName.contains("linux") || osName.contains("nix") || osName.contains("nux")) {
            return Platform.LINUX;
        }

        return Platform.UNKNOWN;
    }

    /**
     * Heuristic to detect Android environments (PojavLauncher, Zalith, etc.).
     *
     * <p>Uses multiple detection methods to maximize compatibility across different
     * Android Minecraft launchers. Each method is logged for diagnostics.</p>
     *
     * <p>Detection methods:</p>
     * <ol>
     *   <li>VM name check (Dalvik/ART)</li>
     *   <li>java.vendor / java.vm.vendor check for "Android"</li>
     *   <li>ANDROID_DATA environment variable</li>
     *   <li>Android system file /system/build.prop</li>
     *   <li>Android launcher package paths (PojavLauncher, Zalith 1/2, etc.)</li>
     *   <li>Generic Android filesystem paths (/data/data, /sdcard, etc.)</li>
     *   <li>Current working directory heuristic (inside /data/data/)</li>
     * </ol>
     */
    private static boolean isAndroidEnvironment(String osName, String osArch) {
        StreamAbleLog.CORE.debug("[AndroidDetect] === Android Detection Start ===");
        StreamAbleLog.CORE.debug("[AndroidDetect] os.name={}", System.getProperty("os.name", "?"));
        StreamAbleLog.CORE.debug("[AndroidDetect] os.arch={}", System.getProperty("os.arch", "?"));
        StreamAbleLog.CORE.debug("[AndroidDetect] java.vm.name={}", System.getProperty("java.vm.name", "?"));
        StreamAbleLog.CORE.debug("[AndroidDetect] java.vm.vendor={}", System.getProperty("java.vm.vendor", "?"));
        StreamAbleLog.CORE.debug("[AndroidDetect] java.vendor={}", System.getProperty("java.vendor", "?"));
        StreamAbleLog.CORE.debug("[AndroidDetect] java.home={}", System.getProperty("java.home", "?"));
        StreamAbleLog.CORE.debug("[AndroidDetect] user.dir={}", System.getProperty("user.dir", "?"));
        StreamAbleLog.CORE.debug("[AndroidDetect] user.home={}", System.getProperty("user.home", "?"));
        StreamAbleLog.CORE.debug("[AndroidDetect] java.runtime.name={}", System.getProperty("java.runtime.name", "?"));
        StreamAbleLog.CORE.debug("[AndroidDetect] ANDROID_DATA={}", System.getenv("ANDROID_DATA"));
        StreamAbleLog.CORE.debug("[AndroidDetect] ANDROID_ROOT={}", System.getenv("ANDROID_ROOT"));

        String vmName = System.getProperty("java.vm.name", "").toLowerCase(Locale.ROOT);
        if (vmName.contains("dalvik") || vmName.contains("art")) {
            StreamAbleLog.CORE.debug("[AndroidDetect] ✓ Detected via java.vm.name: {}", vmName);
            return true;
        }

        String vendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
        String vmVendor = System.getProperty("java.vm.vendor", "").toLowerCase(Locale.ROOT);
        if (vendor.contains("android") || vmVendor.contains("android")) {
            StreamAbleLog.CORE.debug("[AndroidDetect] ✓ Detected via vendor: java.vendor={}, java.vm.vendor={}", vendor, vmVendor);
            return true;
        }

        String androidData = System.getenv("ANDROID_DATA");
        String androidRoot = System.getenv("ANDROID_ROOT");
        if ((androidData != null && !androidData.isEmpty()) || (androidRoot != null && !androidRoot.isEmpty())) {
            StreamAbleLog.CORE.debug("[AndroidDetect] ✓ Detected via env: ANDROID_DATA={}, ANDROID_ROOT={}", androidData, androidRoot);
            return true;
        }

        if (Files.exists(Path.of("/system/build.prop"))) {
            StreamAbleLog.CORE.debug("[AndroidDetect] ✓ Detected via /system/build.prop");
            return true;
        }

        String[] launcherPaths = {
            "/data/data/net.kdt.pojavlaunch",
            "/data/user/0/net.kdt.pojavlaunch",
            "/data/data/com.movtery.pojavzh",
            "/data/user/0/com.movtery.pojavzh",
            "/data/data/com.movtery.zalern",
            "/data/user/0/com.movtery.zalern",
            "/data/data/com.movtery.zalith",
            "/data/user/0/com.movtery.zalith",
            "/data/data/com.movtery.zalithlauncher",
            "/data/user/0/com.movtery.zalithlauncher",
            "/data/data/com.movtery.zalithlauncher.v2",
            "/data/user/0/com.movtery.zalithlauncher.v2",
            "/data/data/com.tungsten.fcl",
            "/data/user/0/com.tungsten.fcl",
        };
        for (String launcherPath : launcherPaths) {
            if (Files.exists(Path.of(launcherPath))) {
                StreamAbleLog.CORE.debug("[AndroidDetect] ✓ Detected via launcher path: {}", launcherPath);
                return true;
            }
        }

        boolean isLinux = osName.contains("linux");
        boolean isArm = osArch.contains("aarch64") || osArch.contains("arm");
        if (isLinux && isArm) {
            if (Files.exists(Path.of("/data/data")) ||
                Files.exists(Path.of("/sdcard")) ||
                Files.exists(Path.of("/storage/emulated"))) {
                StreamAbleLog.CORE.debug("[AndroidDetect] ✓ Detected via Linux+ARM + Android paths");
                return true;
            }
        }

        String userDir = System.getProperty("user.dir", "");
        String javaHome = System.getProperty("java.home", "");
        if (userDir.startsWith("/data/data/") || userDir.startsWith("/data/user/") ||
            javaHome.startsWith("/data/data/") || javaHome.startsWith("/data/user/")) {
            StreamAbleLog.CORE.debug("[AndroidDetect] ✓ Detected via working directory inside /data/: userDir={}, javaHome={}", userDir, javaHome);
            return true;
        }

        try {
            Class.forName("android.os.Build");
            StreamAbleLog.CORE.debug("[AndroidDetect] ✓ Detected via android.os.Build class");
            return true;
        } catch (ClassNotFoundException ignored) {
        }

        StreamAbleLog.CORE.debug("[AndroidDetect] ✗ Not detected as Android");
        StreamAbleLog.CORE.debug("[AndroidDetect] === Android Detection End ===");
        return false;
    }

    public static boolean isWindows() {
        return detectPlatform() == Platform.WINDOWS;
    }

    public static boolean isLinux() {
        return detectPlatform() == Platform.LINUX;
    }

    public static boolean isMacOS() {
        return detectPlatform() == Platform.MACOS;
    }

    public static boolean isAndroid() {
        return detectPlatform() == Platform.ANDROID;
    }

    /**
     * Returns {@code true} if the current platform supports recording.
     *
     * <p>Android is now supported via bundled FFmpeg binaries for ARM architectures
     * (used by PojavLauncher, Zalith, and FoldCraftLauncher). Only {@code UNKNOWN}
     * platforms are unsupported.</p>
     */
    public static boolean isRecordingSupported() {
        Platform platform = detectPlatform();
        return platform != Platform.UNKNOWN;
    }

    /**
     * Returns a platform-specific string identifier compatible with
     * the existing {@code AudioCapture.getPlatform()} format.
     */
    public static String getPlatformId() {
        return switch (detectPlatform()) {
            case WINDOWS -> "windows";
            case LINUX -> "linux";
            case MACOS -> "macos";
            case ANDROID -> "android";
            case UNKNOWN -> "unknown";
        };
    }

    /**
     * Checks if FFmpeg is available. This delegates to {@link FFmpegEncoder#detectFfmpeg()}.
     *
     * @return {@code true} if FFmpeg was found in PATH or via RECORDABLE_FFMPEG_PATH
     */
    public static boolean isFfmpegAvailable() {
        try {
            if (isAndroid()) {
                return FfmpegBundleManager.isBundledFfmpegAvailable();
            }
            return FFmpegManager.detect().found();
        } catch (Exception e) {
            StreamAbleLog.CORE.debug("FFmpeg detection failed", e);
            return false;
        }
    }

    /**
     * Returns a platform-specific hint for installing FFmpeg.
     */
    public static String getFfmpegInstallHint() {
        return switch (detectPlatform()) {
            case WINDOWS -> "Click 'Download FFmpeg' in Stream-able settings (auto-downloads from gyan.dev), "
                    + "or install manually from https://www.gyan.dev/ffmpeg/builds/ and add it to PATH.";
            case LINUX -> "Click 'Download FFmpeg' in Stream-able settings (auto-downloads from johnvansickle.com), "
                    + "or install via your package manager: sudo apt install ffmpeg / sudo dnf install ffmpeg / sudo pacman -S ffmpeg.";
            case MACOS -> "Click 'Download FFmpeg' in Stream-able settings (auto-downloads from evermeet.cx), "
                    + "or install via Homebrew: brew install ffmpeg.";
            case ANDROID -> "Android auto-download is not supported. Install Termux and run 'pkg install ffmpeg', "
                    + "then set ffmpegPath to /data/data/com.termux/files/usr/bin/ffmpeg.";
            case UNKNOWN -> "Please install FFmpeg and ensure it is available in your system PATH.";
        };
    }

    /**
     * Returns a platform-specific description of the audio capture method.
     */
    public static String getAudioMethodDescription() {
        return switch (detectPlatform()) {
            case WINDOWS -> "DirectShow (Stereo Mix)";
            case LINUX -> "PulseAudio";
            case MACOS -> "AVFoundation";
            case ANDROID -> "OpenAL Loopback (game audio)";
            case UNKNOWN -> "Unknown";
        };
    }

    /**
     * Returns {@code true} if the current platform is a mobile/resource-constrained device.
     * Currently this means Android, but could be extended for other mobile platforms.
     */
    public static boolean isMobileDevice() {
        return detectPlatform() == Platform.ANDROID;
    }

    /**
     * Returns the maximum memory budget (in megabytes) for the replay buffer
     * based on the current platform and available heap space.
     *
     * <p>Mobile devices get a conservative 200 MB budget. Desktop platforms
     * get up to 1500 MB, capped at 40% of the max JVM heap to leave room for
     * Minecraft itself.</p>
     */
    public static long getReplayBufferMemoryBudgetMB() {
        long maxHeapMB = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        if (isMobileDevice()) {
            // Android: very conservative — cap at 200 MB or 15% of heap, whichever is smaller
            return Math.min(200L, maxHeapMB * 15 / 100);
        }
        // Desktop: up to 1500 MB, but no more than 40% of heap
        return Math.min(1500L, maxHeapMB * 40 / 100);
    }

    /**
     * Returns an estimated per-frame size in bytes for the replay buffer,
     * based on a given width and height. Accounts for mobile downscaling
     * (50% resolution on Android).
     */
    public static long estimateReplayFrameBytes(int width, int height) {
        if (isMobileDevice()) {
            // On mobile, we store frames at 50% resolution → 25% of full pixels
            return (long) (width / 2) * (height / 2) * 3;
        }
        return (long) width * height * 3;
    }

    /**
     * Returns the downscale factor for replay buffer frames.
     * Mobile devices use 2 (50% resolution), desktop uses 1 (full resolution).
     */
    public static int getReplayBufferDownscaleFactor() {
        return isMobileDevice() ? 2 : 1;
    }

    /**
     * Returns a user-friendly platform status string for the settings screen.
     */
    public static String getPlatformStatusText() {
        Platform platform = detectPlatform();
        boolean ffmpegFound = isFfmpegAvailable();

        if (platform == Platform.ANDROID) {
            boolean bundled = FfmpegBundleManager.isBundledFfmpegAvailable();
            String arch = FfmpegBundleManager.detectArmArchitecture();
            if (bundled) {
                return "✓ Android (" + arch + ") - FFmpeg ready";
            }
            return "⚠ Android (" + arch + ") - FFmpeg not installed (use Termux: pkg install ffmpeg)";
        }
        if (!ffmpegFound) {
            return "✗ FFmpeg not found - " + getFfmpegInstallHint();
        }
        return "✓ " + platform.displayName() + " - Audio: " + getAudioMethodDescription();
    }
}
