package dev.streamable.ffmpeg;

import dev.streamable.runtime.ManagedRuntime;
import dev.streamable.runtime.RuntimeContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The Stream-able-managed FFmpeg build.
 *
 * <p>This is the normal way players get FFmpeg now: a pinned GPL build for
 * Linux x86-64 and Windows x86-64, downloaded over HTTPS, verified against its
 * SHA-256 and unpacked with only {@code ffmpeg}, {@code ffprobe} and the
 * licence kept. Hardware encoders (NVENC, AMF, Quick Sync, VA-API) are compiled
 * into both builds, but whether they work still depends on the GPU driver the
 * player has installed - Stream-able never ships drivers, and
 * {@link FFmpegCapabilityProbe} proves each encoder with a real encode.</p>
 */
public final class FFmpegRuntime extends ManagedRuntime {

    public static final String ID = "ffmpeg";

    private volatile String versionLine = "";

    public FFmpegRuntime(RuntimeContext context) {
        super(context, context.manifest().require(ID));
    }

    private String executableName() {
        return context.platform().isWindows() ? "ffmpeg.exe" : "ffmpeg";
    }

    /** The ffmpeg binary inside an install directory. */
    public Path executableIn(Path directory) {
        return directory.resolve("bin").resolve(executableName());
    }

    /** The binary of the pinned install, if it is installed (not necessarily started). */
    public Optional<Path> installedExecutable() {
        if (!isInstalled()) {
            return Optional.empty();
        }
        Path executable = executableIn(installDirectory());
        return Files.isRegularFile(executable) ? Optional.of(executable) : Optional.empty();
    }

    /** Binaries of older Stream-able-managed installs, newest first. */
    public java.util.List<Path> previousExecutables() {
        return previousInstalls().stream().map(this::executableIn).filter(Files::isRegularFile).toList();
    }

    public String versionLine() {
        return versionLine;
    }

    @Override
    protected void validateInstall(Path directory) throws IOException {
        Path executable = executableIn(directory);
        if (!Files.isRegularFile(executable)) {
            throw new IOException("ffmpeg binary missing from " + directory);
        }
        if (!context.platform().isWindows() && !Files.isExecutable(executable)) {
            throw new IOException("ffmpeg binary is not executable: " + executable);
        }
    }

    /** Proves the verified binary actually runs on this machine. */
    @Override
    protected void initialize(Path directory) throws Exception {
        Path executable = executableIn(directory);
        FFmpegProcesses.Result result = FFmpegProcesses.run(
                java.util.List.of(executable.toString(), "-hide_banner", "-version"), 20);
        String output = result.output();
        if (result.exitCode() != 0) {
            throw new IOException("The downloaded FFmpeg could not run on this system (exit "
                    + result.exitCode() + "): " + output.lines().findFirst().orElse(""));
        }
        versionLine = output.lines().findFirst().orElse("ffmpeg").trim();
    }
}
