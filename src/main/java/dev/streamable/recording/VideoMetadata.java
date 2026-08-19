/*
 * Ported from Record-able by JoEusebe (MIT). See NOTICE for attribution.
 * Adapted for Stream-able: package, logging category and branding only -
 * the capture/encoding behaviour is intentionally unchanged.
 */
package dev.streamable.recording;

import dev.streamable.StreamAbleLog;
import dev.streamable.ffmpeg.FFmpegManager;

import dev.streamable.util.PlatformUtils;

import dev.streamable.ffmpeg.FfmpegBundleManager;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight metadata model + cached extraction for recorded video files. */
public final class VideoMetadata {
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern FILENAME_TIMESTAMP = Pattern.compile("(\\d{8}-\\d{6})");
    /** Matches "Duration: HH:MM:SS.xx" in ffmpeg -i output */
    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "Duration:\\s*(\\d+):(\\d+):(\\d+\\.\\d+)");

    private static final Object LOCK = new Object();
    private static final Map<Path, CacheEntry> CACHE = new HashMap<>();
    private static volatile ProbeStatus cachedProbeStatus;
    private static volatile long cachedProbeAtMs;

    public final Path file;
    public final String filename;
    public final long sizeBytes;
    public final String sizeDisplay;
    public final String durationDisplay;
    public final double durationSeconds;
    public final String recordedAtDisplay;
    public final long modifiedMillis;
    public final Path thumbnailPath;

    private VideoMetadata(Path file,
                          long sizeBytes,
                          String durationDisplay,
                          double durationSeconds,
                          String recordedAtDisplay,
                          long modifiedMillis,
                          Path thumbnailPath) {
        this.file = file;
        this.filename = file == null || file.getFileName() == null ? "?" : file.getFileName().toString();
        this.sizeBytes = sizeBytes;
        this.sizeDisplay = String.format(Locale.ROOT, "%.2f MB", sizeBytes / (1024.0D * 1024.0D));
        this.durationDisplay = durationDisplay;
        this.durationSeconds = durationSeconds;
        this.recordedAtDisplay = recordedAtDisplay;
        this.modifiedMillis = modifiedMillis;
        this.thumbnailPath = thumbnailPath;
    }

    public static VideoMetadata read(Path videoFile) {
        if (videoFile == null) {
            return new VideoMetadata(Path.of("?"), 0L, "?", -1D, "?", 0L, null);
        }

        try {
            Path normalized = videoFile.toAbsolutePath().normalize();
            long size = safeSize(normalized);
            long modified = safeModifiedMillis(normalized);
            CacheEntry cached;
            synchronized (LOCK) {
                cached = CACHE.get(normalized);
                if (cached != null && cached.sizeBytes == size && cached.modifiedMillis == modified) {
                    return cached.metadata;
                }
            }

            double durationSeconds = probeDurationSeconds(normalized);
            String durationDisplay = durationSeconds <= 0D ? "?" : formatDuration(durationSeconds);
            String recordedAt = resolveRecordedAt(normalized, modified);
            Path thumbnail = ensureThumbnail(normalized, modified);

            VideoMetadata metadata = new VideoMetadata(normalized, size, durationDisplay, durationSeconds, recordedAt, modified, thumbnail);
            synchronized (LOCK) {
                CACHE.put(normalized, new CacheEntry(size, modified, metadata));
            }
            return metadata;
        } catch (Throwable throwable) {
            StreamAbleLog.RECORDING.warn("Failed to read video metadata for {}", videoFile, throwable);
            return new VideoMetadata(videoFile, 0L, "?", -1D, "?", safeModifiedMillis(videoFile), null);
        }
    }

    /**
     * Quick read that skips ffprobe (duration will show "..."). Use this for
     * initial UI population, then call {@link #probeDurationFor(Path)} from a
     * background thread to fill in durations without blocking the render thread.
     */
    public static VideoMetadata readQuick(Path videoFile) {
        if (videoFile == null) {
            return new VideoMetadata(Path.of("?"), 0L, "?", -1D, "?", 0L, null);
        }

        try {
            Path normalized = videoFile.toAbsolutePath().normalize();
            long size = safeSize(normalized);
            long modified = safeModifiedMillis(normalized);

            // Return cached entry if still valid (includes duration from prior probe)
            synchronized (LOCK) {
                CacheEntry cached = CACHE.get(normalized);
                if (cached != null && cached.sizeBytes == size && cached.modifiedMillis == modified) {
                    return cached.metadata;
                }
            }

            String recordedAt = resolveRecordedAt(normalized, modified);
            Path thumbnail = ensureThumbnail(normalized, modified);

            // No ffprobe call -- duration is pending
            VideoMetadata metadata = new VideoMetadata(normalized, size, "...", -1D, recordedAt, modified, thumbnail);
            // Do NOT cache this incomplete entry
            return metadata;
        } catch (Throwable throwable) {
            StreamAbleLog.RECORDING.warn("Failed to quick-read video metadata for {}", videoFile, throwable);
            return new VideoMetadata(videoFile, 0L, "?", -1D, "?", safeModifiedMillis(videoFile), null);
        }
    }

    /**
     * Probe the duration for a single file and cache it. Intended to be called
     * from a background thread. Returns the updated metadata or the original
     * if probing fails.
     */
    public static VideoMetadata probeDurationFor(Path videoFile) {
        if (videoFile == null) return null;
        try {
            Path normalized = videoFile.toAbsolutePath().normalize();
            long size = safeSize(normalized);
            long modified = safeModifiedMillis(normalized);

            // Check cache first
            synchronized (LOCK) {
                CacheEntry cached = CACHE.get(normalized);
                if (cached != null && cached.sizeBytes == size && cached.modifiedMillis == modified
                        && cached.metadata.durationSeconds > 0D) {
                    return cached.metadata;
                }
            }

            double durationSeconds = probeDurationSeconds(normalized);
            String durationDisplay = durationSeconds <= 0D ? "?" : formatDuration(durationSeconds);
            String recordedAt = resolveRecordedAt(normalized, modified);
            Path thumbnail = ensureThumbnail(normalized, modified);

            VideoMetadata metadata = new VideoMetadata(normalized, size, durationDisplay, durationSeconds, recordedAt, modified, thumbnail);
            synchronized (LOCK) {
                CACHE.put(normalized, new CacheEntry(size, modified, metadata));
            }
            return metadata;
        } catch (Throwable throwable) {
            StreamAbleLog.RECORDING.debug("Duration probe failed for {}", videoFile, throwable);
            return null;
        }
    }

    public static void clearCache() {
        synchronized (LOCK) {
            CACHE.clear();
        }
    }

    public static boolean isFfprobeAvailable() {
        return detectFfprobe().available;
    }

    private static double probeDurationSeconds(Path file) {
        if (file == null) return -1D;

        // Strategy 1: try ffprobe (preferred, accurate)
        ProbeStatus probe = detectFfprobe();
        if (probe.available) {
            double result = runFfprobeDuration(probe.executable, file);
            if (result > 0D) return result;
            StreamAbleLog.RECORDING.info("[Duration] ffprobe found but failed for {}, trying ffmpeg -i fallback", file.getFileName());
        } else {
            StreamAbleLog.RECORDING.info("[Duration] ffprobe not available, trying ffmpeg -i fallback for {}", file.getFileName());
        }

        // Strategy 2: use "ffmpeg -i" as fallback (always available if recording works)
        double fallback = runFfmpegDurationFallback(file);
        if (fallback > 0D) {
            StreamAbleLog.RECORDING.info("[Duration] ffmpeg -i fallback got duration {}s for {}", String.format(Locale.ROOT, "%.1f", fallback), file.getFileName());
        }
        return fallback;
    }

    /**
     * Runs ffprobe to get the duration of a video file.
     */
    private static double runFfprobeDuration(String executable, Path file) {
        try {
            Process process = new ProcessBuilder(
                    executable,
                    "-nostdin",
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.toAbsolutePath().toString()
            ).redirectErrorStream(true).start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
                output = sb.toString().trim();
            }

            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                StreamAbleLog.RECORDING.warn("[Duration] ffprobe timed out (10s) for {}", file.getFileName());
                return -1D;
            }

            if (output.isBlank()) {
                StreamAbleLog.RECORDING.info("[Duration] ffprobe returned empty output for {}", file.getFileName());
                return -1D;
            }

            for (String candidate : output.split("\n")) {
                String trimmed = candidate.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    double parsed = Double.parseDouble(trimmed);
                    if (parsed > 0) {
                        return parsed;
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            StreamAbleLog.RECORDING.info("[Duration] ffprobe output not parseable for {}: '{}'", file.getFileName(), output);
            return -1D;
        } catch (Throwable throwable) {
            StreamAbleLog.RECORDING.info("[Duration] ffprobe execution error for {}: {}", file.getFileName(), throwable.getMessage());
            return -1D;
        }
    }

    /**
     * Fallback: use "ffmpeg -i <file>" and parse the "Duration: HH:MM:SS.xx" line
     * from stderr. This works whenever ffmpeg is available (which it must be if
     * recordings work at all).
     */
    private static double runFfmpegDurationFallback(Path file) {
        FFmpegManager.Detection ffmpegStatus = FFmpegManager.detect();
        if (!ffmpegStatus.found()) {
            StreamAbleLog.RECORDING.warn("[Duration] ffmpeg not found either -- cannot determine duration for {}", file.getFileName());
            return -1D;
        }

        try {
            // "ffmpeg -i <file>" prints info to stderr and exits with code 1 (no output specified).
            // We parse the "Duration: HH:MM:SS.xx" line from the output.
            Process process = new ProcessBuilder(
                    ffmpegStatus.executable(),
                    "-nostdin",
                    "-hide_banner",
                    "-i", file.toAbsolutePath().toString()
            ).redirectErrorStream(true).start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
                output = sb.toString();
            }

            // Don't care about exit code -- ffmpeg -i always exits 1 (no output file)
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                StreamAbleLog.RECORDING.warn("[Duration] ffmpeg -i timed out (10s) for {}", file.getFileName());
                return -1D;
            }

            // Parse "Duration: HH:MM:SS.xx" from the output
            // Pattern matches lines like: "  Duration: 01:23:45.67, start: ..."
            java.util.regex.Matcher matcher = DURATION_PATTERN.matcher(output);
            if (matcher.find()) {
                int hours = Integer.parseInt(matcher.group(1));
                int minutes = Integer.parseInt(matcher.group(2));
                double seconds = Double.parseDouble(matcher.group(3));
                return hours * 3600D + minutes * 60D + seconds;
            }

            StreamAbleLog.RECORDING.info("[Duration] ffmpeg -i output did not contain duration for {}", file.getFileName());
            return -1D;
        } catch (Throwable throwable) {
            StreamAbleLog.RECORDING.info("[Duration] ffmpeg -i fallback error for {}: {}", file.getFileName(), throwable.getMessage());
            return -1D;
        }
    }

    private static ProbeStatus detectFfprobe() {
        long now = System.currentTimeMillis();
        ProbeStatus cached = cachedProbeStatus;
        if (cached != null && now - cachedProbeAtMs < 30_000L) {
            return cached;
        }

        StreamAbleLog.RECORDING.info("[ffprobe] Starting detection...");

        // 1. User-configured env var
        String configured = System.getenv("RECORDABLE_FFPROBE_PATH");
        if (configured != null && !configured.isBlank()) {
            StreamAbleLog.RECORDING.info("[ffprobe] Step 1: checking env var RECORDABLE_FFPROBE_PATH = '{}'", configured.trim());
            ProbeStatus userStatus = probeExecutable(configured.trim());
            if (userStatus.available) {
                StreamAbleLog.RECORDING.info("[ffprobe] Found via env var: {}", configured.trim());
                cachedProbeStatus = userStatus;
                cachedProbeAtMs = now;
                return userStatus;
            }
            StreamAbleLog.RECORDING.info("[ffprobe] Env var path not working");
        }

        // 2. Bundled/downloaded ffprobe next to ffmpeg
        try {
            Path bundleDir = FfmpegBundleManager.getBundleDirectory();
            StreamAbleLog.RECORDING.info("[ffprobe] Step 2: checking bundle dir: {} (exists={})",
                    bundleDir, bundleDir != null && Files.isDirectory(bundleDir));
            if (bundleDir != null) {
                String probeName = PlatformUtils.isWindows() ? "ffprobe.exe" : "ffprobe";
                Path bundledProbe = bundleDir.resolve(probeName);
                boolean isFile = Files.isRegularFile(bundledProbe);
                boolean isReadable = isFile && Files.isReadable(bundledProbe);
                StreamAbleLog.RECORDING.info("[ffprobe]   Candidate: {} (isFile={}, isReadable={})",
                        bundledProbe, isFile, isReadable);
                if (isFile && isReadable) {
                    ProbeStatus bundledStatus = probeExecutable(bundledProbe.toAbsolutePath().toString());
                    if (bundledStatus.available) {
                        StreamAbleLog.RECORDING.info("[ffprobe] Found in bundle dir: {}", bundledProbe);
                        cachedProbeStatus = bundledStatus;
                        cachedProbeAtMs = now;
                        return bundledStatus;
                    }
                    StreamAbleLog.RECORDING.info("[ffprobe]   File exists but execution check failed");
                }
            }
        } catch (Throwable t) {
            StreamAbleLog.RECORDING.warn("[ffprobe] Error checking bundled path: {}", t.getMessage());
        }

        // 3. Derive ffprobe from detected ffmpeg path (same directory)
        try {
            FFmpegManager.Detection ffmpegStatus = FFmpegManager.detect();
            StreamAbleLog.RECORDING.info("[ffprobe] Step 3: ffmpeg found={}, executable='{}'",
                    ffmpegStatus.found(), ffmpegStatus.found() ? ffmpegStatus.executable() : "n/a");
            if (ffmpegStatus.found()) {
                Path ffmpegPath = Path.of(ffmpegStatus.executable());
                Path parent = ffmpegPath.getParent();
                StreamAbleLog.RECORDING.info("[ffprobe]   ffmpeg parent dir: {}", parent);
                if (parent != null) {
                    String probeName = PlatformUtils.isWindows() ? "ffprobe.exe" : "ffprobe";
                    Path siblingProbe = parent.resolve(probeName);
                    boolean siblingExists = Files.isRegularFile(siblingProbe);
                    StreamAbleLog.RECORDING.info("[ffprobe]   Sibling candidate: {} (exists={})", siblingProbe, siblingExists);
                    if (siblingExists) {
                        ProbeStatus siblingStatus = probeExecutable(siblingProbe.toAbsolutePath().toString());
                        if (siblingStatus.available) {
                            StreamAbleLog.RECORDING.info("[ffprobe] Found as sibling of ffmpeg: {}", siblingProbe);
                            cachedProbeStatus = siblingStatus;
                            cachedProbeAtMs = now;
                            return siblingStatus;
                        }
                        StreamAbleLog.RECORDING.info("[ffprobe]   Sibling exists but execution check failed");
                    }
                } else {
                    StreamAbleLog.RECORDING.info("[ffprobe]   ffmpeg path has no parent (bare command name on PATH)");
                }
            }
        } catch (Throwable t) {
            StreamAbleLog.RECORDING.warn("[ffprobe] Error deriving from ffmpeg path: {}", t.getMessage());
        }

        // 4. System PATH fallback
        StreamAbleLog.RECORDING.info("[ffprobe] Step 4: trying system PATH 'ffprobe'...");
        ProbeStatus systemStatus = probeExecutable("ffprobe");
        StreamAbleLog.RECORDING.info("[ffprobe] System PATH result: available={}", systemStatus.available);

        if (!systemStatus.available) {
            StreamAbleLog.RECORDING.info("[ffprobe] NOT FOUND via any method. Duration will use ffmpeg -i fallback.");
        }

        cachedProbeStatus = systemStatus;
        cachedProbeAtMs = now;
        return systemStatus;
    }

    private static ProbeStatus probeExecutable(String executable) {
        try {
            Process process = new ProcessBuilder(executable, "-version").redirectErrorStream(true).start();
            boolean exited = process.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                StreamAbleLog.RECORDING.info("[ffprobe] Probe timed out (5s) for: {}", executable);
                return new ProbeStatus(false, executable);
            }
            boolean ok = process.exitValue() == 0;
            if (!ok) {
                StreamAbleLog.RECORDING.info("[ffprobe] Probe returned exit code {} for: {}", process.exitValue(), executable);
            }
            return new ProbeStatus(ok, executable);
        } catch (Throwable throwable) {
            StreamAbleLog.RECORDING.info("[ffprobe] Probe exception for '{}': {}", executable, throwable.getMessage());
            return new ProbeStatus(false, executable);
        }
    }

    private static Path ensureThumbnail(Path videoFile, long modifiedMillis) {
        if (videoFile == null) {
            return null;
        }

        FFmpegManager.Detection ffmpeg = FFmpegManager.detect();
        if (!ffmpeg.found()) {
            StreamAbleLog.RECORDING.debug("Skipping thumbnail for {} because ffmpeg was not found.", videoFile);
            return null;
        }

        Path thumbnailDir = FabricLoader.getInstance().getGameDir().resolve("stream-able").resolve("thumbnails");

        try {
            Files.createDirectories(thumbnailDir);
            if (!Files.isDirectory(thumbnailDir) || !Files.isWritable(thumbnailDir)) {
                StreamAbleLog.RECORDING.warn("Thumbnail directory is not writable: {}", thumbnailDir);
                return null;
            }

            String key = sha1(videoFile.toString() + ":" + modifiedMillis);
            Path thumbnailPath = thumbnailDir.resolve(key + ".png");
            if (Files.exists(thumbnailPath) && safeSize(thumbnailPath) > 0L) {
                return thumbnailPath;
            }

            ProcessBuilder builder = new ProcessBuilder(
                    ffmpeg.executable(),
                    "-nostdin",
                    "-hide_banner",
                    "-loglevel", "error",
                    "-y",
                    "-i", videoFile.toAbsolutePath().toString(),
                    "-ss", "00:00:00.500",
                    "-frames:v", "1",
                    "-vf", "thumbnail,scale=192:-1",
                    thumbnailPath.toAbsolutePath().toString()
            );
            builder.redirectErrorStream(true);
            Process process = builder.start();

            boolean exited = process.waitFor(4, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                StreamAbleLog.RECORDING.debug("Thumbnail extraction timed out for {}", videoFile);
                return null;
            }

            String ffmpegOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() == 0 && Files.exists(thumbnailPath) && safeSize(thumbnailPath) > 0L) {
                return thumbnailPath;
            }

            if (!ffmpegOutput.isBlank()) {
                StreamAbleLog.RECORDING.debug("Thumbnail extraction failed for {}: {}", videoFile, ffmpegOutput);
            } else {
                StreamAbleLog.RECORDING.debug("Thumbnail extraction failed for {} with exit code {}", videoFile, process.exitValue());
            }
        } catch (Throwable throwable) {
            StreamAbleLog.RECORDING.debug("Thumbnail extraction failed for {}", videoFile, throwable);
        }

        return null;
    }

    private static String resolveRecordedAt(Path videoFile, long modifiedMillis) {
        try {
            String name = videoFile == null || videoFile.getFileName() == null ? "" : videoFile.getFileName().toString();
            Matcher matcher = FILENAME_TIMESTAMP.matcher(name);
            if (matcher.find()) {
                LocalDateTime parsed = LocalDateTime.parse(matcher.group(1), FILE_TS);
                return DISPLAY_TS.format(parsed);
            }
        } catch (Throwable ignored) {
        }

        Instant instant = Instant.ofEpochMilli(Math.max(0L, modifiedMillis));
        return DISPLAY_TS.format(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()));
    }

    private static String formatDuration(double durationSeconds) {
        long seconds = Math.max(0L, (long) Math.round(durationSeconds));
        long hours = seconds / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long remaining = seconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remaining);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, remaining);
    }

    private static long safeSize(Path path) {
        if (path == null) {
            return 0L;
        }
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static long safeModifiedMillis(Path path) {
        if (path == null) {
            return 0L;
        }
        try {
            FileTime modified = Files.getLastModifiedTime(path);
            return modified.toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (Throwable throwable) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private record CacheEntry(long sizeBytes, long modifiedMillis, VideoMetadata metadata) {
    }

    private record ProbeStatus(boolean available, String executable) {
    }
}
