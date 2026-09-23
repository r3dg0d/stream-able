package dev.streamable.ffmpeg;

import dev.streamable.StreamAbleLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Determines which encoders actually work on this machine.
 *
 * <p>{@code ffmpeg -encoders} lists everything the binary was <em>built</em>
 * with, which says nothing about whether the GPU, driver or kernel module is
 * present: a laptop with no NVIDIA card still lists {@code h264_nvenc}, and a
 * driver that is too old for the NVENC API the build targets fails at open.
 * Each candidate is therefore given a real short encode - 30 frames of 720p,
 * through the same device setup and pixel-format path the real pipeline uses -
 * before it is offered.</p>
 *
 * <p>Probing runs on a background executor ({@link #probeAllAsync}). Results
 * are cached until the FFmpeg binary changes.</p>
 */
public final class FFmpegCapabilityProbe {

    private static final long PROBE_TIMEOUT_SECONDS = 20;
    private static final int PROBE_FRAMES = 30;

    /** Outcome of one encoder's test encode. */
    public record Result(VideoEncoder encoder, boolean usable, String reason, double framesPerSecond) {
        public static Result notListed(VideoEncoder encoder) {
            return new Result(encoder, false, "Not included in this FFmpeg build.", 0);
        }
    }

    private final FFmpegManager ffmpeg;
    private final Map<VideoEncoder, Result> cache = new EnumMap<>(VideoEncoder.class);
    private volatile List<String> listedEncoders;
    private volatile CompletableFuture<Void> probeJob;

    public FFmpegCapabilityProbe(FFmpegManager ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    /** Clears cached results, e.g. after the FFmpeg binary changed. */
    public synchronized void invalidate() {
        cache.clear();
        listedEncoders = null;
        probeJob = null;
    }

    /** Probes every known encoder in the background. Returns the in-flight job if one exists. */
    public synchronized CompletableFuture<Void> probeAllAsync(Executor executor) {
        CompletableFuture<Void> current = probeJob;
        if (current != null) {
            return current;
        }
        probeJob = CompletableFuture.runAsync(() -> {
            for (VideoEncoder encoder : VideoEncoder.values()) {
                isUsable(encoder);
            }
            StreamAbleLog.FFMPEG.info("Encoder probe complete: {}", summary());
        }, executor);
        return probeJob;
    }

    /** Whether every encoder has been tested. */
    public synchronized boolean isComplete() {
        return cache.size() == VideoEncoder.values().length;
    }

    /** Snapshot of results so far, for the Video and Runtime pages. */
    public synchronized List<Result> results() {
        return List.copyOf(cache.values());
    }

    public synchronized String summary() {
        StringBuilder text = new StringBuilder();
        for (Result result : cache.values()) {
            if (result.usable()) {
                if (!text.isEmpty()) {
                    text.append(", ");
                }
                text.append(result.encoder().ffmpegName());
            }
        }
        return text.isEmpty() ? "no working encoders" : text.toString();
    }

    /** Encoder names the binary advertises. Cheap; used to skip pointless probes. */
    private List<String> listedEncoders() {
        List<String> current = listedEncoders;
        if (current != null) {
            return current;
        }
        List<String> names = new ArrayList<>();
        RunResult run = run(List.of(ffmpeg.executable(), "-hide_banner", "-encoders"));
        if (run.exitCode == 0) {
            for (String line : run.output.split("\\R")) {
                String trimmed = line.trim();
                // Format: "V....D h264_nvenc    NVIDIA NVENC H.264 encoder"
                if (trimmed.length() > 8 && (trimmed.charAt(0) == 'V' || trimmed.charAt(0) == 'A')) {
                    String[] parts = trimmed.split("\\s+");
                    if (parts.length >= 2) {
                        names.add(parts[1]);
                    }
                }
            }
        }
        listedEncoders = List.copyOf(names);
        return listedEncoders;
    }

    /** The test-encode command, using the same device and format plumbing as real encodes. */
    static List<String> probeCommand(String executable, VideoEncoder encoder) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        command.addAll(EncoderArgs.deviceArgs(encoder));
        command.add("-f");
        command.add("lavfi");
        command.add("-i");
        command.add("testsrc2=s=1280x720:r=60");
        command.add("-frames:v");
        command.add(Integer.toString(PROBE_FRAMES));
        command.add("-vf");
        command.add(EncoderArgs.formatFilter(encoder));
        command.add("-c:v");
        command.add(encoder.ffmpegName());
        command.add("-f");
        command.add("null");
        command.add("-");
        return command;
    }

    /** Whether an encoder is genuinely usable. Blocks while a test encode runs. */
    public synchronized boolean isUsable(VideoEncoder encoder) {
        return result(encoder).usable();
    }

    public synchronized Result result(VideoEncoder encoder) {
        Result cached = cache.get(encoder);
        if (cached != null) {
            return cached;
        }
        Result result;
        if (!ffmpeg.isAvailable()) {
            result = new Result(encoder, false, "FFmpeg is not available yet.", 0);
            return result;   // not cached: FFmpeg may arrive later
        } else if (!listedEncoders().contains(encoder.ffmpegName())) {
            result = Result.notListed(encoder);
        } else {
            long start = System.nanoTime();
            RunResult run = run(probeCommand(ffmpeg.executable(), encoder));
            double seconds = (System.nanoTime() - start) / 1e9;
            if (run.exitCode == 0) {
                result = new Result(encoder, true, "Test encode succeeded.", PROBE_FRAMES / Math.max(1e-3, seconds));
            } else {
                String reason = explain(run.output);
                result = new Result(encoder, false, reason, 0);
                StreamAbleLog.FFMPEG.debug("Encoder {} failed its test encode: {}", encoder.ffmpegName(), reason);
            }
        }
        cache.put(encoder, result);
        return result;
    }

    /** Turns FFmpeg's failure output into a sentence a player can act on. */
    static String explain(String output) {
        String lower = output.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("minimum required nvidia driver") || lower.contains("required nvenc api version")) {
            return "NVIDIA driver is too old for this FFmpeg build's NVENC. Update the GPU driver.";
        }
        if (lower.contains("cannot load libcuda") || lower.contains("cannot load nvcuda")) {
            return "No NVIDIA driver (CUDA) found.";
        }
        if (lower.contains("no capable devices found") || lower.contains("openencodesessionex failed")) {
            return "No NVENC-capable NVIDIA GPU found.";
        }
        if (lower.contains("libva") || lower.contains("vaapi") || lower.contains("renderd")) {
            return "VA-API is not available (driver or libva missing).";
        }
        if (lower.contains("amf") || lower.contains("libamfrt")) {
            return "AMD AMF runtime not found.";
        }
        if (lower.contains("mfx session") || lower.contains("qsv") || lower.contains("libvpl")) {
            return "Intel Quick Sync is not available.";
        }
        String first = output.lines().filter(line -> !line.isBlank()).findFirst().orElse("").trim();
        return first.isEmpty() ? "The test encode failed." : first;
    }

    /** Every encoder that passed its test, in preference order. */
    public List<VideoEncoder> availableEncoders(boolean streamSafeOnly) {
        List<VideoEncoder> available = new ArrayList<>();
        for (VideoEncoder candidate : VideoEncoder.autoDetectOrder(streamSafeOnly)) {
            if (isUsable(candidate)) {
                available.add(candidate);
            }
        }
        return List.copyOf(available);
    }

    /**
     * Picks the best working encoder.
     *
     * <p>Falls back to software x264 even if the probe failed, because a
     * best-effort attempt with a clear error beats refusing to start at all.</p>
     */
    public VideoEncoder bestEncoder(boolean streamSafeOnly) {
        for (VideoEncoder candidate : VideoEncoder.autoDetectOrder(streamSafeOnly)) {
            if (isUsable(candidate)) {
                return candidate;
            }
        }
        return VideoEncoder.X264;
    }

    /** Resolves a configured encoder name, falling back to auto-detection. */
    public VideoEncoder resolve(String configuredName, boolean streamSafeOnly) {
        if (configuredName == null || configuredName.isBlank()) {
            return bestEncoder(streamSafeOnly);
        }
        VideoEncoder requested = VideoEncoder.byFfmpegName(configuredName);
        if (isUsable(requested)) {
            return requested;
        }
        VideoEncoder fallback = bestEncoder(streamSafeOnly);
        StreamAbleLog.FFMPEG.warn("Encoder {} is not usable here ({}); falling back to {}.",
                configuredName, result(requested).reason(), fallback.ffmpegName());
        return fallback;
    }

    private record RunResult(int exitCode, String output) {
    }

    private static RunResult run(List<String> command) {
        if (command.getFirst().isEmpty()) {
            return new RunResult(-1, "");
        }
        try {
            Process process = FFmpegProcesses.builder(command).redirectErrorStream(true).start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new RunResult(-1, "Timed out.");
            }
            return new RunResult(process.exitValue(), output);
        } catch (IOException e) {
            return new RunResult(-1, e.getMessage() == null ? "" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RunResult(-1, "Interrupted.");
        }
    }
}
