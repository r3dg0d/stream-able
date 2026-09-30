package dev.streamable.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Opens a folder in the desktop's file manager.
 *
 * <p>Minecraft used to offer this ({@code Util.getPlatform().openPath}) but 26.3
 * removed it, so Stream-able launches the platform's opener itself. The command
 * is an argument list, never a shell string, so a path with spaces or shell
 * characters is passed through untouched.</p>
 */
public final class FolderOpener {

    private FolderOpener() {
    }

    /** The command that opens {@code dir} on {@code platform}. */
    static List<String> command(PlatformUtils.Platform platform, Path dir) {
        String path = dir.toAbsolutePath().toString();
        return switch (platform) {
            case WINDOWS -> List.of("explorer.exe", path);
            case MACOS -> List.of("open", path);
            default -> List.of("xdg-open", path);
        };
    }

    /**
     * Creates the folder if needed and asks the desktop to open it.
     *
     * @throws IOException if the folder cannot be created or no opener could be started
     */
    public static void open(Path dir) throws IOException {
        Files.createDirectories(dir);
        // Not waited for: explorer.exe exits non-zero even when it worked, and
        // xdg-open can stay alive as long as the file manager does.
        new ProcessBuilder(command(PlatformUtils.detectPlatform(), dir))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
