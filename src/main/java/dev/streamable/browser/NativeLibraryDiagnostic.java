package dev.streamable.browser;

import dev.streamable.StreamAbleLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explains why a native library failed to load.
 *
 * <p>Linux reports a missing <em>dependency</em> with the same message it uses
 * for a missing library:</p>
 *
 * <pre>
 *   .../libjcef.so: cannot open shared object file: No such file or directory
 * </pre>
 *
 * <p>naming the top-level library even when that file is present and only
 * something it links against is absent. Taken at face value the message sends
 * people looking for a file that is right there, so this class checks whether
 * the named file actually exists and, if it does, asks {@code ldd} which of its
 * dependencies could not be resolved.</p>
 *
 * <p>The distinction matters most on distributions without a standard FHS
 * layout - NixOS, Guix - where prebuilt binaries like Chromium cannot find
 * system libraries at all and need {@code nix-ld}, {@code steam-run} or an FHS
 * environment. That is a property of the machine, not something a mod can
 * repair, so the best it can do is say so precisely.</p>
 */
public final class NativeLibraryDiagnostic {

    /**
     * Matches a native library path inside an error message.
     *
     * <p>Covers a POSIX path ending in {@code .so}/{@code .so.N} or
     * {@code .dylib}, and a Windows path ending in {@code .dll}. The Windows
     * branch is matched first because a drive letter contains the {@code :}
     * that terminates the POSIX branch.</p>
     */
    private static final Pattern LIBRARY_PATH = Pattern.compile(
            "([A-Za-z]:\\\\[^\\n\"]*?\\.dll)|(/[^:\\n]*?(?:\\.so(?:\\.\\d+)*|\\.dylib))");
    private static final Pattern LDD_MISSING = Pattern.compile("^\\s*(\\S+)\\s*=>\\s*not found\\s*$");
    private static final long LDD_TIMEOUT_SECONDS = 15;

    private NativeLibraryDiagnostic() {
    }

    /**
     * Builds a human-readable explanation for a failed native load.
     *
     * @param error the throwable chain from the failed initialisation
     * @return an explanation, or {@code null} if this was not a native-load failure
     */
    public static String explain(Throwable error) {
        UnsatisfiedLinkError linkError = findLinkError(error);
        if (linkError == null) {
            return null;
        }
        String message = linkError.getMessage() == null ? "" : linkError.getMessage();
        Path library = extractLibraryPath(message);

        if (library == null) {
            return "The browser engine's native library could not be loaded: " + message;
        }
        if (!Files.isRegularFile(library)) {
            return "The browser engine's native library is missing: " + library
                    + ". Delete the mcef-modern config folder so it downloads again.";
        }

        // The file exists, so something it depends on is what is actually missing.
        List<String> missing = missingDependencies(library);
        StringBuilder explanation = new StringBuilder();
        explanation.append("The browser engine's native library exists but cannot be loaded, ")
                .append("because libraries it depends on are missing from this system. ")
                .append("(Linux reports this as \"cannot open shared object file\" against ")
                .append(library.getFileName()).append(" itself, which is misleading.)");

        if (!missing.isEmpty()) {
            explanation.append("\n\nMissing: ").append(String.join(", ", missing));
        }
        String guidance = platformGuidance(missing);
        if (guidance != null) {
            explanation.append("\n\n").append(guidance);
        }
        explanation.append("\n\nRecording and streaming are unaffected; only browser sources are disabled.");
        return explanation.toString();
    }

    private static UnsatisfiedLinkError findLinkError(Throwable error) {
        for (Throwable current = error; current != null && current.getCause() != current;
             current = current.getCause()) {
            if (current instanceof UnsatisfiedLinkError link) {
                return link;
            }
        }
        return null;
    }

    static Path extractLibraryPath(String message) {
        Matcher matcher = LIBRARY_PATH.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        String path = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        try {
            return Path.of(path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** MCEF's native library file name for the current platform. */
    public static String nativeLibraryFileName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "jcef.dll";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "libjcef.dylib";
        }
        return "libjcef.so";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Asks {@code ldd} which dependencies cannot be resolved. Empty if unavailable. */
    static List<String> missingDependencies(Path library) {
        if (isWindows()) {
            // No equivalent ships with Windows; the guidance below covers the
            // realistic causes instead of guessing at a dependency list.
            return List.of();
        }
        Set<String> missing = new LinkedHashSet<>();
        try {
            Process process = new ProcessBuilder("ldd", library.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(LDD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            for (String line : output.split("\\R")) {
                Matcher matcher = LDD_MISSING.matcher(line);
                if (matcher.matches()) {
                    missing.add(matcher.group(1));
                }
            }
        } catch (IOException e) {
            // ldd is not present everywhere; the explanation is still useful.
            StreamAbleLog.BROWSER.debug("Could not run ldd: {}", e.toString());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
        return List.copyOf(missing);
    }

    /** Distribution-specific advice for resolving the missing libraries. */
    private static String platformGuidance(List<String> missing) {
        if (isWindows()) {
            return """
                    On Windows this is almost always one of two things: the \
                    Microsoft Visual C++ Redistributable (x64) is not installed, \
                    or the browser runtime download was interrupted and left a \
                    partial copy. Install the redistributable from Microsoft, or \
                    delete the mcef-modern folder in your instance's config \
                    directory so it downloads again.""";
        }
        if (isNixOs()) {
            return """
                    This is NixOS, which has no /usr/lib, so prebuilt binaries like Chromium \
                    cannot find system libraries on their own. Run the launcher inside an FHS \
                    environment, for example:

                      nix-shell -p steam-run --run "steam-run prismlauncher"

                    or enable nix-ld system-wide and list the libraries above in \
                    programs.nix-ld.libraries (nss, nspr, glib, gtk3, at-spi2-atk, cups, dbus, \
                    libdrm, mesa, expat, xorg libraries, libxkbcommon, pango, cairo, alsa-lib \
                    and stdenv.cc.cc.lib cover a Chromium build).""";
        }
        if (missing.isEmpty()) {
            return null;
        }
        List<String> hints = new ArrayList<>();
        for (String library : missing) {
            String lower = library.toLowerCase(Locale.ROOT);
            if (lower.startsWith("libnss") || lower.startsWith("libnspr") || lower.startsWith("libsmime")) {
                hints.add("nss/nspr");
            } else if (lower.startsWith("libgtk") || lower.startsWith("libgdk")) {
                hints.add("gtk3");
            } else if (lower.startsWith("libx") || lower.startsWith("libxcb")) {
                hints.add("X11 client libraries");
            } else if (lower.startsWith("libgbm") || lower.startsWith("libdrm")) {
                hints.add("mesa (libgbm/libdrm)");
            } else if (lower.startsWith("libasound")) {
                hints.add("alsa-lib");
            } else if (lower.startsWith("libcups")) {
                hints.add("cups");
            } else if (lower.startsWith("libstdc++")) {
                hints.add("the C++ standard library");
            }
        }
        if (hints.isEmpty()) {
            return "Install the packages providing the libraries listed above.";
        }
        return "Install the packages providing: " + String.join(", ", new LinkedHashSet<>(hints)) + ".";
    }

    /** True on NixOS, where prebuilt binaries need special handling. */
    public static boolean isNixOs() {
        if (Files.exists(Path.of("/etc/NIXOS"))) {
            return true;
        }
        try {
            Path osRelease = Path.of("/etc/os-release");
            return Files.isRegularFile(osRelease)
                    && Files.readString(osRelease).toLowerCase(Locale.ROOT).contains("nixos");
        } catch (IOException e) {
            return false;
        }
    }
}
