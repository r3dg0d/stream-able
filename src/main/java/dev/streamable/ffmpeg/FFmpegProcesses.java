package dev.streamable.ffmpeg;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Creates every FFmpeg {@link ProcessBuilder}, so all launches share one
 * environment policy.
 *
 * <p>Always an argument array - never a shell string - so a stream key or a
 * path with spaces cannot change the meaning of a command.</p>
 *
 * <h2>Driver libraries</h2>
 * <p>Hardware encoders are loaded by FFmpeg at runtime with {@code dlopen}
 * ({@code libcuda.so.1} for NVENC, {@code libva} for VA-API). On NixOS the GPU
 * driver's libraries live in {@code /run/opengl-driver/lib}, which is not on
 * the default search path, so a static FFmpeg build reports "Cannot load
 * libcuda.so.1" even though the driver is installed. That directory is added
 * to {@code LD_LIBRARY_PATH} for FFmpeg only - never for the game - and only
 * when it exists. On conventional distributions and Windows nothing changes.</p>
 */
public final class FFmpegProcesses {

    /** Driver directories that are real, standard locations but not on the default search path. */
    static final List<String> EXTRA_DRIVER_DIRECTORIES = List.of("/run/opengl-driver/lib");

    private FFmpegProcesses() {
    }

    public static ProcessBuilder builder(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        applyEnvironment(builder.environment(), osName(), EXTRA_DRIVER_DIRECTORIES);
        return builder;
    }

    public record Result(int exitCode, String output) { }

    /** Runs a finite command, draining its pipe concurrently with the deadline. */
    public static Result run(List<String> command, long timeoutSeconds) throws IOException, InterruptedException {
        Process process = builder(command).redirectErrorStream(true).start();
        FutureTask<String> output = new FutureTask<>(() -> {
            try (var in = process.getInputStream()) {
                ByteArrayOutputStream captured = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    // Keep diagnostics bounded, but continue draining to avoid
                    // blocking a noisy encoder on a full OS pipe.
                    int remaining = 64 * 1024 - captured.size();
                    if (remaining > 0) captured.write(buffer, 0, Math.min(count, remaining));
                }
                return captured.toString(StandardCharsets.UTF_8);
            }
        });
        Thread reader = Thread.ofPlatform().daemon(true).name("streamable-ffmpeg-output").start(output);
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("FFmpeg process timed out after " + timeoutSeconds + " seconds.");
            }
            try {
                return new Result(process.exitValue(), output.get(1, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                throw new IOException("Could not read FFmpeg output.", e.getCause());
            } catch (TimeoutException e) {
                throw new IOException("FFmpeg output did not close after exit.", e);
            }
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try {
                    process.waitFor(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            reader.interrupt();
        }
    }

    /** Pure, testable environment adjustment. */
    static void applyEnvironment(Map<String, String> environment, String osName, List<String> candidates) {
        if (!osName.toLowerCase(Locale.ROOT).contains("linux")) {
            return;
        }
        List<String> existing = new ArrayList<>();
        String current = environment.getOrDefault("LD_LIBRARY_PATH", "");
        for (String entry : current.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                existing.add(entry);
            }
        }
        List<String> additions = new ArrayList<>();
        for (String candidate : candidates) {
            if (!existing.contains(candidate) && Files.isDirectory(Path.of(candidate))) {
                additions.add(candidate);
            }
        }
        if (additions.isEmpty()) {
            return;
        }
        // Appended, not prepended: anything the user configured still wins.
        List<String> combined = new ArrayList<>(existing);
        combined.addAll(additions);
        environment.put("LD_LIBRARY_PATH", String.join(File.pathSeparator, combined));
    }

    private static String osName() {
        return System.getProperty("os.name", "");
    }
}
