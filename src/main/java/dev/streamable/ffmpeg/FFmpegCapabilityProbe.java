package dev.streamable.ffmpeg;

import dev.streamable.StreamAbleLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Determines which encoders actually work on this machine.
 *
 * <p>{@code ffmpeg -encoders} lists everything the binary was <em>built</em>
 * with, which says nothing about whether the GPU, driver or kernel module is
 * present: a laptop with no NVIDIA card still lists {@code h264_nvenc}, and
 * selecting it fails at the worst possible moment - when the user presses
 * "Start Streaming". Each candidate is therefore given a real one-frame encode
 * before being offered.</p>
 *
 * <p>Results are cached for the session because the probe costs a process
 * launch per encoder.</p>
 */
public final class FFmpegCapabilityProbe {

    private static final long PROBE_TIMEOUT_SECONDS = 20;

    private final FFmpegManager ffmpeg;
    private final Map<VideoEncoder, Boolean> cache = new EnumMap<>(VideoEncoder.class);
    private volatile List<String> listedEncoders;

    public FFmpegCapabilityProbe(FFmpegManager ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    /** Clears cached results, e.g. after the user changes the FFmpeg path. */
    public synchronized void invalidate() {
        cache.clear();
        listedEncoders = null;
    }

    /** Encoder names the binary advertises. Cheap; used to skip pointless probes. */
    private List<String> listedEncoders() {
        List<String> current = listedEncoders;
        if (current != null) {
            return current;
        }
        List<String> names = new ArrayList<>();
        String output = run(List.of(ffmpeg.executable(), "-hide_banner", "-encoders"));
        if (output != null) {
            for (String line : output.split("\\R")) {
                String trimmed = line.trim();
                // Format: " V....D h264_nvenc    NVIDIA NVENC H.264 encoder"
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

    /**
     * Whether an encoder is genuinely usable.
     *
     * <p>Encodes one frame of black through the encoder to {@code -f null}, which
     * exercises the full hardware initialisation path without writing anything.</p>
     */
    public synchronized boolean isUsable(VideoEncoder encoder) {
        Boolean cached = cache.get(encoder);
        if (cached != null) {
            return cached;
        }
        boolean usable = false;
        if (ffmpeg.isAvailable() && listedEncoders().contains(encoder.ffmpegName())) {
            String output = run(List.of(
                    ffmpeg.executable(), "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-f", "lavfi", "-i", "color=c=black:s=320x240:r=30:d=0.1",
                    "-c:v", encoder.ffmpegName(),
                    "-frames:v", "1",
                    "-f", "null", "-"));
            usable = output != null;
            if (!usable) {
                StreamAbleLog.FFMPEG.debug("Encoder {} is listed but failed its test encode.",
                        encoder.ffmpegName());
            }
        }
        cache.put(encoder, usable);
        return usable;
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
        StreamAbleLog.FFMPEG.warn("Encoder {} is not usable here; falling back to {}.",
                configuredName, fallback.ffmpegName());
        return fallback;
    }

    /** Runs a command, returning its output, or {@code null} on non-zero exit. */
    private static String run(List<String> command) {
        if (command.getFirst().isEmpty()) {
            return null;
        }
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? output : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
