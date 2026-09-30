package dev.streamable.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FolderOpenerTest {

    private static final Path DIR = Path.of("/tmp/my recordings; rm -rf x");

    @Test
    void usesTheNativeOpenerPerPlatform() {
        String path = DIR.toAbsolutePath().toString();
        assertEquals(List.of("xdg-open", path), FolderOpener.command(PlatformUtils.Platform.LINUX, DIR));
        assertEquals(List.of("open", path), FolderOpener.command(PlatformUtils.Platform.MACOS, DIR));
        assertEquals(List.of("explorer.exe", path), FolderOpener.command(PlatformUtils.Platform.WINDOWS, DIR));
    }

    @Test
    void keepsAPathWithSpacesAndShellCharactersAsOneArgument() {
        List<String> command = FolderOpener.command(PlatformUtils.Platform.LINUX, DIR);
        assertEquals(2, command.size());
        assertEquals(DIR.toAbsolutePath().toString(), command.get(1));
    }
}
