/*
 * Ported from Record-able by JoEusebe (MIT). See NOTICE for attribution.
 * Adapted for Stream-able: package, logging category and branding only -
 * the capture/encoding behaviour is intentionally unchanged.
 */
package dev.streamable.audio;

import dev.streamable.StreamAbleLog;

import dev.streamable.util.PlatformUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audio device detection and probing for FFmpeg-based audio capture.
 *
 * <p>This class detects available audio capture devices on the current platform
 * and provides the appropriate FFmpeg input arguments. It supports Windows
 * (DirectShow), Linux (PulseAudio), and macOS (AVFoundation).</p>
 */
public final class AudioCapture {

    /** Result of audio device probing. */
    public record AudioDeviceStatus(
            boolean available,
            String deviceName,
            String platform,
            String message,
            List<String> ffmpegArgs
    ) {
        public static AudioDeviceStatus unavailable(String platform, String message) {
            return new AudioDeviceStatus(false, "", platform, message, Collections.emptyList());
        }

        public static AudioDeviceStatus found(String deviceName, String platform, String message, List<String> ffmpegArgs) {
            return new AudioDeviceStatus(true, deviceName, platform, message, ffmpegArgs);
        }

        public String displayText() {
            if (available) {
                return "Audio: " + deviceName + " (" + platform + ")";
            }
            return "Audio: Not available - " + message;
        }
    }

    private static volatile AudioDeviceStatus cachedStatus;
    private static volatile CacheKey cachedKey;
    private static volatile long cachedStatusAtMs;
    private static final long CACHE_TTL_MS = 10_000L;

    private static final Pattern DSHOW_DEVICE_NAME = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern DSHOW_AUDIO_DEVICE = Pattern.compile("\"([^\"]+)\"\\s+\\(audio\\)");
    private static final Pattern DSHOW_ALTERNATIVE_NAME = Pattern.compile("Alternative name\\s+\"([^\"]+)\"");
    private static final Pattern AVFOUNDATION_DEVICE_NAME = Pattern.compile("\\[(\\d+)]\\s+(.+)$");

    private AudioCapture() {
    }

    /**
     * Detects available audio capture devices using Java's built-in sound API.
     * This works independently of FFmpeg and can be used as a fallback.
     *
     * @return list of available audio device names that support capture
     */
    public static List<String> detectJavaSoundDevices() {
        return JavaAudioCapture.getAvailableDeviceNames();
    }

    /**
     * Checks if any loopback audio device is available via Java sound API.
     * Loopback devices (Stereo Mix, PulseAudio monitors, etc.) capture system audio.
     *
     * @return true if a loopback device is available
     */
    public static boolean isJavaLoopbackAvailable() {
        return JavaAudioCapture.isLoopbackAvailable();
    }

    /**
     * Checks if any audio capture device is available via Java sound API.
     *
     * @return true if at least one capture device exists
     */
    public static boolean isJavaCaptureAvailable() {
        return JavaAudioCapture.isAnyCaptureDeviceAvailable();
    }

    /**
     * Returns a status description for Java audio capture availability,
     * suitable for display in the settings screen.
     *
     * @return human-readable status string
     */
    public static String getJavaAudioStatus() {
        List<JavaAudioCapture.AudioDeviceInfo> devices = JavaAudioCapture.detectAudioDevices();
        if (devices.isEmpty()) {
            return "No audio capture devices detected";
        }

        long loopbackCount = devices.stream().filter(JavaAudioCapture.AudioDeviceInfo::isLoopback).count();
        if (loopbackCount > 0) {
            String bestDevice = devices.stream()
                    .filter(JavaAudioCapture.AudioDeviceInfo::isLoopback)
                    .findFirst()
                    .map(JavaAudioCapture.AudioDeviceInfo::name)
                    .orElse("Unknown");
            return "Loopback: " + bestDevice + " (" + loopbackCount + " device" + (loopbackCount > 1 ? "s" : "") + ")";
        }

        return devices.size() + " capture device" + (devices.size() > 1 ? "s" : "") + " (no loopback)";
    }

    /**
     * Detects the best available audio capture device for the current platform.
     * Results are cached for {@value CACHE_TTL_MS}ms.
     *
     * @param ffmpegExecutable the FFmpeg executable path (from FfmpegStatus)
     * @param configuredDevice user-configured device name, or "auto" for auto-detect
     * @return status with device info and FFmpeg args
     */
    public static AudioDeviceStatus detectAudioDevice(String ffmpegExecutable, String configuredDevice) {
        String executable = ffmpegExecutable == null || ffmpegExecutable.isBlank() ? "ffmpeg" : ffmpegExecutable.trim();
        String configured = configuredDevice == null ? "" : configuredDevice.trim();
        if (isAutoDevicePreference(configured)) {
            configured = "auto";
        }
        CacheKey requestedKey = new CacheKey(executable, configured, getPlatform());

        long now = System.currentTimeMillis();
        AudioDeviceStatus cached = cachedStatus;
        CacheKey key = cachedKey;
        if (cached != null && key != null && requestedKey.equals(key) && now - cachedStatusAtMs < CACHE_TTL_MS) {
            return cached;
        }

        AudioDeviceStatus status = probeAudioDevice(executable, configured);
        cachedStatus = status;
        cachedKey = requestedKey;
        cachedStatusAtMs = now;
        return status;
    }

    /** Clears the cached status so the next call to detectAudioDevice re-probes. */
    public static void clearCache() {
        cachedStatus = null;
        cachedKey = null;
        cachedStatusAtMs = 0L;
    }

    /**
     * Returns the OS category: "windows", "linux", "macos", "android", or "unknown".
     * Delegates to {@link PlatformUtils} for consistent platform detection.
     */
    public static String getPlatform() {
        return PlatformUtils.getPlatformId();
    }

    /**
     * Runs a short FFmpeg probe to verify that the selected audio device can be opened.
     * This does not guarantee non-silent input, but catches common binding failures.
     */
    public static boolean testAudioDevice(String ffmpegExecutable, AudioDeviceStatus status) {
        if (status == null || !status.available()) {
            return false;
        }

        String executable = ffmpegExecutable == null || ffmpegExecutable.isBlank() ? "ffmpeg" : ffmpegExecutable.trim();
        List<String> args = status.ffmpegArgs();
        if (args == null || args.isEmpty()) {
            args = switch (status.platform() == null ? "" : status.platform().toLowerCase(Locale.ROOT)) {
                case "windows" -> buildWindowsInputArgs(status.deviceName());
                case "linux" -> List.of("-f", "pulse", "-i", status.deviceName());
                case "macos" -> List.of("-f", "avfoundation", "-i", ":" + status.deviceName());
                default -> Collections.emptyList();
            };
        }
        if (args.isEmpty()) {
            return false;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.add("-nostdin");
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.addAll(args);
        cmd.add("-t");
        cmd.add("1");
        cmd.add("-f");
        cmd.add("null");
        cmd.add("-");

        ProcessResult result = runCommand(6, cmd.toArray(String[]::new));
        if (!result.success()) {
            logProcessOutput("Audio device probe", result);
        }
        return result.success();
    }

    private static AudioDeviceStatus probeAudioDevice(String ffmpegExecutable, String configuredDevice) {
        String platform = getPlatform();
        boolean isAuto = isAutoDevicePreference(configuredDevice);

        return switch (platform) {
            case "linux" -> probeLinuxAudio(isAuto ? null : configuredDevice);
            case "windows" -> probeWindowsAudio(ffmpegExecutable, isAuto ? null : configuredDevice);
            case "macos" -> probeMacOSAudio(ffmpegExecutable, isAuto ? null : configuredDevice);
            case "android" -> probeAndroidAudio();
            default -> AudioDeviceStatus.unavailable(platform, "Unsupported operating system for audio capture.");
        };
    }

    private static AudioDeviceStatus probeAndroidAudio() {
        try {
            OpenALLoopbackCapture loopback = OpenALLoopbackCapture.getInstance();
            if (loopback.isActive()) {
                return AudioDeviceStatus.found(
                        "OpenAL Loopback",
                        "android",
                        "Game audio captured directly via OpenAL loopback (48kHz Stereo).",
                        Collections.emptyList()
                );
            }
        } catch (Throwable ignored) {}

        try {
            if (OpenALLoopbackCapture.isLoopbackSupported()) {
                return AudioDeviceStatus.found(
                        "OpenAL Loopback (pending)",
                        "android",
                        "OpenAL loopback supported. Audio will activate when recording starts.",
                        Collections.emptyList()
                );
            }
        } catch (Throwable ignored) {}

        return AudioDeviceStatus.found(
                "OpenAL Capture",
                "android",
                "Audio captured via OpenAL capture device.",
                Collections.emptyList()
        );
    }

    private static AudioDeviceStatus probeLinuxAudio(String explicitDevice) {
        if (explicitDevice != null) {
            List<String> args = List.of("-f", "pulse", "-i", explicitDevice);
            return AudioDeviceStatus.found(explicitDevice, "linux",
                    "Using configured PulseAudio source: " + explicitDevice, args);
        }

        String monitorSource = findPulseMonitorSource();
        if (monitorSource != null) {
            List<String> args = List.of("-f", "pulse", "-i", monitorSource);
            return AudioDeviceStatus.found(monitorSource, "linux",
                    "PulseAudio monitor source detected.", args);
        }

        List<String> args = List.of("-f", "pulse", "-i", "default");
        return AudioDeviceStatus.found("default", "linux",
                "Using default PulseAudio source (monitor source not found).", args);
    }

    /**
     * Uses {@code pactl list short sources} to find a monitor source.
     */
    private static String findPulseMonitorSource() {
        ProcessResult result = runCommand(3, "pactl", "list", "short", "sources");
        if (!result.success() && !result.hasAnyOutput()) {
            return null;
        }

        List<String> monitors = new ArrayList<>();
        for (String line : mergeOutputLines(result)) {
            String[] parts = line.split("\\t");
            if (parts.length < 2) {
                continue;
            }
            String sourceName = parts[1].trim();
            if (sourceName.endsWith(".monitor")) {
                monitors.add(sourceName);
            }
        }

        if (monitors.isEmpty()) {
            return null;
        }
        for (String monitor : monitors) {
            if (monitor.contains("analog")) {
                return monitor;
            }
        }
        return monitors.get(0);
    }

    private static AudioDeviceStatus probeWindowsAudio(String ffmpegExecutable, String explicitDevice) {
        if (explicitDevice != null) {
            String trimmedDevice = explicitDevice.trim();
            if (trimmedDevice.isEmpty()) {
                return AudioDeviceStatus.unavailable("windows", "Configured audio device name is empty.");
            }

            String dshowDevice = resolveDshowCaptureTarget(ffmpegExecutable, trimmedDevice);
            List<String> args = buildWindowsInputArgs(dshowDevice);
            return AudioDeviceStatus.found(dshowDevice, "windows",
                    "Using configured DirectShow device: " + dshowDevice, args);
        }

        String dshowDevice = detectWindowsAudioDirectShow(ffmpegExecutable);
        if (dshowDevice != null) {
            String captureTarget = resolveDshowCaptureTarget(ffmpegExecutable, dshowDevice);
            List<String> args = buildWindowsInputArgs(captureTarget);
            return AudioDeviceStatus.found(captureTarget, "windows",
                    "DirectShow audio device detected (Stereo Mix/loopback input).", args);
        }

        return AudioDeviceStatus.unavailable("windows",
                "Stereo Mix was not detected. Recording will continue in video-only mode. " +
                        "Enable Stereo Mix in Windows Sound Settings > Recording > Show Disabled Devices, then retry.");
    }

    private static String detectWindowsAudioDirectShow(String ffmpegExecutable) {
        StreamAbleLog.AUDIO.info("Scanning Windows audio devices via DirectShow...");
        List<String> dshowDevices = listDShowAudioDevices(ffmpegExecutable);
        StreamAbleLog.AUDIO.info("DirectShow audio devices found: {}", dshowDevices);
        if (dshowDevices.isEmpty()) {
            return null;
        }

        String best = pickPreferredDevice(dshowDevices, new String[]{
                "stereo mix", "what u hear", "cable output", "voicemeeter", "vb-audio", "loopback",
                "speakers", "headphones", "headset", "realtek"
        });
        if (best != null) {
            return best;
        }
        return dshowDevices.get(0);
    }

    private static List<String> buildWindowsInputArgs(String deviceName) {
        if (deviceName == null || deviceName.isBlank()) {
            return Collections.emptyList();
        }

        String trimmed = deviceName.trim();

        return List.of(
                "-rtbufsize", "200M",
                "-f", "dshow",
                "-audio_buffer_size", "30",
                "-i", "audio=" + trimmed
        );
    }

    private static String resolveDshowCaptureTarget(String ffmpegExecutable, String preferredDevice) {
        if (preferredDevice == null || preferredDevice.isBlank()) {
            return preferredDevice;
        }

        String trimmed = preferredDevice.trim();
        if (trimmed.startsWith("@device_")) {
            return trimmed;
        }

        StreamAbleLog.AUDIO.info("Using DirectShow device name as-is: {}", trimmed);
        return trimmed;
    }

    private static String findDshowAlternativeName(String ffmpegExecutable, String deviceName) {
        ProcessResult result = runCommand(8,
                ffmpegExecutable,
                "-hide_banner",
                "-list_devices", "true",
                "-f", "dshow",
                "-i", "dummy");

        List<String> outputLines = mergeOutputLines(result);
        if (outputLines.isEmpty()) {
            return null;
        }

        boolean matchedDevice = false;
        for (String line : outputLines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();

            Matcher deviceMatcher = DSHOW_AUDIO_DEVICE.matcher(trimmed);
            if (deviceMatcher.find()) {
                String candidate = deviceMatcher.group(1).trim();
                matchedDevice = candidate.equalsIgnoreCase(deviceName);
                continue;
            }

            if (!matchedDevice) {
                continue;
            }

            Matcher altMatcher = DSHOW_ALTERNATIVE_NAME.matcher(trimmed);
            if (altMatcher.find()) {
                String alt = altMatcher.group(1).trim();
                if (alt.toLowerCase(Locale.ROOT).contains("/wave_")) {
                    return alt;
                }
            }
        }

        return null;
    }

    private static String pickPreferredDevice(List<String> devices, String[] priorityTokens) {
        if (devices == null || devices.isEmpty()) {
            return null;
        }

        for (String token : priorityTokens) {
            String loweredToken = token.toLowerCase(Locale.ROOT);
            for (String device : devices) {
                if (device != null && device.toLowerCase(Locale.ROOT).contains(loweredToken)) {
                    return device;
                }
            }
        }

        return null;
    }

    private static boolean isAutoDevicePreference(String configuredDevice) {
        if (configuredDevice == null) {
            return true;
        }
        String normalized = configuredDevice.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || "auto".equals(normalized) || "openal".equals(normalized);
    }

    /**
     * Runs {@code ffmpeg -list_devices true -f dshow -i dummy} to list DirectShow audio devices.
     * Parses device names from FFmpeg stderr output.
     *
     * <p>FFmpeg outputs device listings in this format:</p>
     * <pre>
     * [dshow @ 0x...] "Device Name" (video)
     * [dshow @ 0x...]   Alternative name "@device_..."
     * [dshow @ 0x...] "Device Name" (audio)
     * [dshow @ 0x...]   Alternative name "@device_..."
     * </pre>
     *
     * <p>Or in newer FFmpeg versions:</p>
     * <pre>
     * [in#0 @ 0x...] "Device Name" (audio)
     * [in#0 @ 0x...]   Alternative name "@device_cm_..."
     * </pre>
     *
     * <p>We match lines containing {@code "NAME" (audio)} using the
     * {@link #DSHOW_AUDIO_DEVICE} pattern, which is reliable across FFmpeg versions.</p>
     */
    private static List<String> listDShowAudioDevices(String ffmpegExecutable) {
        ProcessResult result = runCommand(8,
                ffmpegExecutable,
                "-hide_banner",
                "-list_devices", "true",
                "-f", "dshow",
                "-i", "dummy");

        logProcessOutput("DirectShow device detection", result);

        List<String> outputLines = mergeOutputLines(result);
        if (outputLines.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> devices = new ArrayList<>();

        for (String line : outputLines) {
            if (line == null) {
                continue;
            }

            String trimmed = line.trim();

            if (trimmed.toLowerCase(Locale.ROOT).contains("alternative name")) {
                continue;
            }

            Matcher matcher = DSHOW_AUDIO_DEVICE.matcher(trimmed);
            while (matcher.find()) {
                String candidate = matcher.group(1).trim();
                if (!candidate.isEmpty() && !devices.contains(candidate)) {
                    devices.add(candidate);
                    StreamAbleLog.AUDIO.info("Found DirectShow audio device: {}", candidate);
                }
            }
        }

        return devices;
    }

    private static AudioDeviceStatus probeMacOSAudio(String ffmpegExecutable, String explicitDevice) {
        if (explicitDevice != null) {
            List<String> args;
            if (explicitDevice.matches("\\d+")) {
                args = List.of("-f", "avfoundation", "-i", ":" + explicitDevice);
            } else {
                args = List.of("-f", "avfoundation", "-i", ":" + explicitDevice);
            }
            return AudioDeviceStatus.found(explicitDevice, "macos",
                    "Using configured AVFoundation device: " + explicitDevice, args);
        }

        List<String> avDevices = listAVFoundationAudioDevices(ffmpegExecutable);

        String[] preferredDevices = {"BlackHole", "Soundflower", "Loopback", "Multi-Output"};
        for (int i = 0; i < avDevices.size(); i++) {
            String device = avDevices.get(i);
            for (String preferred : preferredDevices) {
                if (device.toLowerCase(Locale.ROOT).contains(preferred.toLowerCase(Locale.ROOT))) {
                    List<String> args = List.of("-f", "avfoundation", "-i", ":" + i);
                    return AudioDeviceStatus.found(device, "macos",
                            "Virtual audio device detected for system audio capture.", args);
                }
            }
        }

        if (!avDevices.isEmpty()) {
            List<String> args = List.of("-f", "avfoundation", "-i", ":0");
            return AudioDeviceStatus.found(avDevices.get(0), "macos",
                    "Using default audio input. Install BlackHole for system audio capture.",
                    args);
        }

        return AudioDeviceStatus.unavailable("macos",
                "No AVFoundation audio devices detected. Install BlackHole or Soundflower for system audio capture.");
    }

    private static List<String> listAVFoundationAudioDevices(String ffmpegExecutable) {
        ProcessResult result = runCommand(5,
                ffmpegExecutable,
                "-hide_banner",
                "-f", "avfoundation",
                "-list_devices", "true",
                "-i", "");
        if (!result.success() && !result.hasAnyOutput()) {
            return Collections.emptyList();
        }

        List<String> devices = new ArrayList<>();
        boolean inAudioSection = false;
        for (String line : mergeOutputLines(result)) {
            String trimmed = line.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.contains("avfoundation audio devices")) {
                inAudioSection = true;
                continue;
            }
            if (lower.contains("avfoundation video devices")) {
                inAudioSection = false;
                continue;
            }
            if (!inAudioSection) {
                continue;
            }

            Matcher matcher = AVFOUNDATION_DEVICE_NAME.matcher(trimmed);
            if (matcher.find()) {
                String name = matcher.group(2).trim();
                if (!name.isEmpty()) {
                    devices.add(name);
                }
            }
        }

        return devices;
    }

    private static ProcessResult runCommand(int timeoutSeconds, String... command) {
        List<String> stdoutLines = Collections.synchronizedList(new ArrayList<>());
        List<String> stderrLines = Collections.synchronizedList(new ArrayList<>());

        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();

            Thread stdoutThread = new Thread(() -> readProcessStream(process.getInputStream(), stdoutLines),
                    "Stream-able cmd stdout");
            Thread stderrThread = new Thread(() -> readProcessStream(process.getErrorStream(), stderrLines),
                    "Stream-able cmd stderr");
            stdoutThread.setDaemon(true);
            stderrThread.setDaemon(true);
            stdoutThread.start();
            stderrThread.start();

            boolean exited = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
            }

            joinThreadQuietly(stdoutThread, 500L);
            joinThreadQuietly(stderrThread, 500L);

            int exitCode = exited ? process.exitValue() : -1;
            boolean success = exited && exitCode == 0;
            String error = success ? "" : (exited ? "Exit code " + exitCode : "Timed out");

            return new ProcessResult(
                    success,
                    List.copyOf(stdoutLines),
                    List.copyOf(stderrLines),
                    exitCode,
                    error
            );
        } catch (Exception exception) {
            StreamAbleLog.AUDIO.debug("Failed to execute command: {}", String.join(" ", command), exception);
            String message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
            return new ProcessResult(false, Collections.emptyList(), Collections.emptyList(), -1, message);
        }
    }

    private static void readProcessStream(java.io.InputStream stream, List<String> sink) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sink.add(line);
            }
        } catch (Exception exception) {
            StreamAbleLog.AUDIO.debug("Failed to read process stream", exception);
        }
    }

    private static void joinThreadQuietly(Thread thread, long timeoutMs) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(Math.max(1L, timeoutMs));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> mergeOutputLines(ProcessResult result) {
        if (result == null) {
            return Collections.emptyList();
        }
        List<String> merged = new ArrayList<>(result.stderrLines());
        if (merged.isEmpty()) {
            merged.addAll(result.stdoutLines());
        } else if (!result.stdoutLines().isEmpty()) {
            merged.addAll(result.stdoutLines());
        }
        return merged;
    }

    private static void logProcessOutput(String label, ProcessResult result) {
        if (result == null) {
            return;
        }

        String stdoutContent = result.stdoutLines().isEmpty()
                ? "<empty>"
                : String.join(System.lineSeparator(), result.stdoutLines());
        String stderrContent = result.stderrLines().isEmpty()
                ? "<empty>"
                : String.join(System.lineSeparator(), result.stderrLines());

        StreamAbleLog.AUDIO.info("{} exitCode={} success={} error='{}'", label, result.exitCode(), result.success(), result.error());
        StreamAbleLog.AUDIO.info("{} STDOUT:{}{}", label, System.lineSeparator(), stdoutContent);
        StreamAbleLog.AUDIO.info("{} STDERR:{}{}", label, System.lineSeparator(), stderrContent);
    }

    private record ProcessResult(boolean success, List<String> stdoutLines, List<String> stderrLines, int exitCode,
                                 String error) {
        private boolean hasAnyOutput() {
            return !stdoutLines.isEmpty() || !stderrLines.isEmpty();
        }
    }

    private record CacheKey(String ffmpegExecutable, String configuredDevice, String platform) {
        private CacheKey {
            ffmpegExecutable = ffmpegExecutable == null ? "" : ffmpegExecutable;
            configuredDevice = configuredDevice == null ? "" : configuredDevice;
            platform = platform == null ? "unknown" : platform;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CacheKey other)) {
                return false;
            }
            return Objects.equals(ffmpegExecutable, other.ffmpegExecutable)
                    && Objects.equals(configuredDevice, other.configuredDevice)
                    && Objects.equals(platform, other.platform);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ffmpegExecutable, configuredDevice, platform);
        }
    }
}
