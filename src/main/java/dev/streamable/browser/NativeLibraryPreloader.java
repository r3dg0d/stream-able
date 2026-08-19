package dev.streamable.browser;

import dev.streamable.StreamAbleLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves Chromium's native dependencies on systems without a standard FHS layout.
 *
 * <h2>The problem</h2>
 * <p>MCEF downloads a prebuilt {@code libjcef.so}/{@code libcef.so} that links
 * against ~28 system libraries. On NixOS and similar distributions there is no
 * {@code /usr/lib}, so the dynamic linker cannot find them and the load fails
 * with a message naming {@code libjcef.so} itself.</p>
 *
 * <p>The usual answer is to launch the game with {@code LD_LIBRARY_PATH} set.
 * That works, but it depends on launcher configuration that the launcher itself
 * may rewrite, and it has to be redone whenever the JDK or launcher changes.</p>
 *
 * <h2>The approach</h2>
 * <p>{@code LD_LIBRARY_PATH} is read by glibc once at process start, so setting
 * it from inside the JVM is useless. What <em>does</em> work is loading the
 * dependencies explicitly: once a library is in the process image, the linker
 * satisfies later {@code DT_NEEDED} references by SONAME without searching the
 * filesystem. So this locates the missing libraries in whatever directories the
 * system does provide and loads them before MCEF touches Chromium.</p>
 *
 * <p>Libraries are loaded in a fixed-point loop rather than a computed
 * dependency order: each pass loads whatever succeeds, and anything that failed
 * because its own dependencies were not up yet is retried on the next pass.
 * That converges without needing to model the dependency graph.</p>
 *
 * <p>This only ever loads libraries the linker <em>cannot already find</em>, so
 * it cannot shadow a library the game has correctly resolved by another route.
 * On a normal FHS system it does nothing at all.</p>
 */
public final class NativeLibraryPreloader {

    /**
     * Chromium's dependencies, used when the native library has not been
     * downloaded yet and {@code ldd} therefore has nothing to inspect.
     */
    private static final List<String> KNOWN_CHROMIUM_DEPENDENCIES = List.of(
            "libstdc++.so.6", "libglib-2.0.so.0", "libgobject-2.0.so.0", "libgio-2.0.so.0",
            "libnspr4.so", "libnss3.so", "libnssutil3.so", "libsmime3.so",
            "libdbus-1.so.3", "libatk-1.0.so.0", "libatk-bridge-2.0.so.0", "libatspi.so.0",
            "libcups.so.2", "libexpat.so.1", "libdrm.so.2", "libgbm.so.1",
            "libX11.so.6", "libXcomposite.so.1", "libXdamage.so.1", "libXext.so.6",
            "libXfixes.so.3", "libXrandr.so.2", "libxcb.so.1", "libxkbcommon.so.0",
            "libpango-1.0.so.0", "libcairo.so.2", "libasound.so.2", "libudev.so.1",
            "libjawt.so");

    /** Passes before giving up; dependency chains here are only a few deep. */
    private static final int MAX_PASSES = 8;

    private static boolean attempted;

    private NativeLibraryPreloader() {
    }

    /**
     * Loads Chromium's dependencies if the system cannot resolve them itself.
     *
     * <p>Safe and cheap to call when nothing is wrong: it returns immediately on
     * non-Linux systems and whenever the linker can already satisfy everything.</p>
     *
     * @param jcefLibrary the expected {@code libjcef.so} path; may not exist yet
     * @return the number of libraries loaded
     */
    public static synchronized int preloadIfNeeded(Path jcefLibrary) {
        if (attempted || !isLinux()) {
            return 0;
        }
        attempted = true;

        Set<String> needed = determineMissing(jcefLibrary);
        if (needed.isEmpty()) {
            return 0;
        }
        List<Path> searchPath = searchDirectories();
        if (searchPath.isEmpty()) {
            StreamAbleLog.BROWSER.debug("No candidate library directories; skipping preload.");
            return 0;
        }

        StreamAbleLog.BROWSER.info(
                "Chromium's native dependencies are not on this system's library path "
                        + "({} missing); loading them directly.", needed.size());

        Set<String> pending = new LinkedHashSet<>(needed);
        int loaded = 0;
        for (int pass = 0; pass < MAX_PASSES && !pending.isEmpty(); pass++) {
            boolean progress = false;
            for (var iterator = pending.iterator(); iterator.hasNext(); ) {
                String soname = iterator.next();
                if (load(soname, searchPath)) {
                    iterator.remove();
                    loaded++;
                    progress = true;
                }
            }
            if (!progress) {
                break;   // remaining entries are genuinely unavailable
            }
        }

        if (pending.isEmpty()) {
            StreamAbleLog.BROWSER.info("Loaded {} native libraries; browser sources should start normally.", loaded);
        } else {
            StreamAbleLog.BROWSER.warn(
                    "Loaded {} native libraries, but these could not be found: {}. "
                            + "Browser sources will probably not start.", loaded, pending);
        }
        return loaded;
    }

    /** Tries each candidate directory for one SONAME. */
    private static boolean load(String soname, List<Path> searchPath) {
        for (Path directory : searchPath) {
            Path candidate = directory.resolve(soname);
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                System.load(candidate.toAbsolutePath().toString());
                return true;
            } catch (UnsatisfiedLinkError e) {
                // Usually "its own dependencies are not loaded yet" - the next
                // pass retries once more of the set is in place.
                StreamAbleLog.BROWSER.debug("Deferring {}: {}", soname, e.getMessage());
            } catch (SecurityException | UnsupportedOperationException e) {
                StreamAbleLog.BROWSER.debug("Cannot load {}: {}", soname, e.toString());
                return false;
            }
        }
        return false;
    }

    /**
     * The libraries that need loading.
     *
     * <p>When the native library exists, {@code ldd} gives the exact answer.
     * Before the first download it does not exist yet, so a known list is used
     * instead - otherwise the first launch would always fail and only work after
     * a restart.</p>
     */
    private static Set<String> determineMissing(Path jcefLibrary) {
        if (jcefLibrary != null && Files.isRegularFile(jcefLibrary)) {
            return new LinkedHashSet<>(NativeLibraryDiagnostic.missingDependencies(jcefLibrary));
        }
        if (!looksNonFhs()) {
            return Set.of();
        }
        // Only take the ones the linker genuinely cannot resolve on its own.
        Set<String> missing = new LinkedHashSet<>();
        for (String soname : KNOWN_CHROMIUM_DEPENDENCIES) {
            if (!isResolvable(soname)) {
                missing.add(soname);
            }
        }
        return missing;
    }

    /** Whether the linker can already find a library by SONAME. */
    private static boolean isResolvable(String soname) {
        for (Path directory : List.of(Path.of("/usr/lib/x86_64-linux-gnu"), Path.of("/usr/lib64"),
                Path.of("/usr/lib"), Path.of("/lib/x86_64-linux-gnu"), Path.of("/lib64"))) {
            if (Files.isRegularFile(directory.resolve(soname))) {
                return true;
            }
        }
        return false;
    }

    /** True on systems without the usual FHS library directories. */
    private static boolean looksNonFhs() {
        return NativeLibraryDiagnostic.isNixOs()
                || (!Files.isDirectory(Path.of("/usr/lib/x86_64-linux-gnu"))
                && !Files.isDirectory(Path.of("/usr/lib64")));
    }

    /**
     * Directories to search, most specific first.
     *
     * <p>{@code NIX_LD_LIBRARY_PATH} is included because on NixOS it is exactly
     * the curated set of libraries intended for unpatched binaries, and the JDK's
     * own directory because {@code libjawt.so} ships with Java rather than the
     * distribution.</p>
     */
    private static List<Path> searchDirectories() {
        List<Path> directories = new ArrayList<>();
        addPathList(directories, System.getenv("NIX_LD_LIBRARY_PATH"));
        addIfDirectory(directories, Path.of("/run/current-system/sw/share/nix-ld/lib"));
        addPathList(directories, System.getenv("LD_LIBRARY_PATH"));
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            addIfDirectory(directories, Path.of(javaHome, "lib"));
        }
        return directories;
    }

    private static void addPathList(List<Path> directories, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String entry : value.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                addIfDirectory(directories, Path.of(entry));
            }
        }
    }

    private static void addIfDirectory(List<Path> directories, Path directory) {
        try {
            if (Files.isDirectory(directory) && !directories.contains(directory)) {
                directories.add(directory);
            }
        } catch (RuntimeException e) {
            StreamAbleLog.BROWSER.debug("Skipping library directory {}: {}", directory, e.toString());
        }
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    /** Test seam: allows a fresh attempt. */
    static synchronized void resetForTesting() {
        attempted = false;
    }
}
