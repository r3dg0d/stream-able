package dev.streamable.runtime;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/**
 * Shared collaborators for every managed runtime.
 *
 * @param root       {@code .minecraft/stream-able/runtime}
 * @param platform   the platform being installed for
 * @param manifest   pinned component list
 * @param downloader verified downloader
 * @param installer  atomic installer
 * @param executor   background executor; never the render thread
 */
public record RuntimeContext(Path root, RuntimePlatform platform, RuntimeManifest manifest,
                             RuntimeDownloader downloader, RuntimeInstaller installer, ExecutorService executor) {

    /** Where verified archives are cached while installing. */
    public Path downloads() {
        return root.resolve("downloads");
    }
}
