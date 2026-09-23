package dev.streamable.browser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.streamable.runtime.ManagedRuntime;
import dev.streamable.runtime.RuntimeContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The Chromium (JCEF/CEF) native runtime that browser sources need.
 *
 * <h2>Why Stream-able installs it rather than MCEF</h2>
 * <p>MCEF Modern ships inside Stream-able (Jar-in-Jar), but its downloader -
 * jcefmaven - fetches the natives without any checksum and extracts tar entries
 * with {@code new File(installDir, entry.getName())}, i.e. with no traversal
 * protection. Stream-able therefore installs the exact artifact MCEF expects
 * (Maven Central {@code me.friwi:jcef-natives}, pinned by SHA-256) through its
 * own hardened installer, into the directory MCEF reads, and writes the
 * {@code install.lock} marker jcefmaven checks. When MCEF later initialises it
 * finds a complete installation and never downloads anything itself. MCEF is
 * not initialised at all until this succeeds.</p>
 *
 * <p>The directory is MCEF's own ({@code config/mcef-modern/jcef}) so that a
 * player who also installs a standalone MCEF build of the same version shares
 * one copy of Chromium.</p>
 */
public final class BrowserRuntime extends ManagedRuntime {

    public static final String ID = "jcef-natives";

    private final Path installDirectory;

    public BrowserRuntime(RuntimeContext context, Path mcefJcefDirectory) {
        super(context, context.manifest().require(ID));
        this.installDirectory = mcefJcefDirectory;
    }

    @Override
    public Path installDirectory() {
        return installDirectory;
    }

    @Override
    protected Path componentDirectory() {
        return installDirectory.getParent();
    }

    /** Only one version lives in MCEF's directory; there is nothing older to fall back to. */
    @Override
    public List<Path> previousInstalls() {
        return List.of();
    }

    /** jcefmaven's platform identifier. */
    String jcefPlatform() {
        return switch (context.platform().id()) {
            case "linux-x86_64" -> "linux-amd64";
            case "linux-aarch64" -> "linux-arm64";
            case "windows-x86_64" -> "windows-amd64";
            case "macos-x86_64" -> "macosx-amd64";
            case "macos-aarch64" -> "macosx-arm64";
            default -> context.platform().id();
        };
    }

    private String nativeLibrary() {
        return context.platform().isWindows() ? "jcef.dll" : "libjcef.so";
    }

    @Override
    protected void postInstall(Path stagedDirectory) throws IOException {
        // The marker jcefmaven's CefInstallationChecker requires. Written only
        // after the verified archive fully extracted into the staging tree.
        Files.writeString(stagedDirectory.resolve("install.lock"), "", StandardCharsets.UTF_8);
    }

    @Override
    protected void validateInstall(Path directory) throws IOException {
        validate(directory, descriptor.version(), jcefPlatform(), nativeLibrary());
    }

    /** Pure validation of an install tree, shared with tests. */
    static void validate(Path directory, String expectedTag, String expectedPlatform, String nativeLibrary)
            throws IOException {
        Path meta = directory.resolve("build_meta.json");
        if (!Files.isRegularFile(meta) || !Files.isRegularFile(directory.resolve("install.lock"))) {
            throw new IOException("Browser engine install is incomplete");
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Browser engine build_meta.json is unreadable", e);
        }
        String tag = json.has("release_tag") ? json.get("release_tag").getAsString() : "";
        String platform = json.has("platform") ? json.get("platform").getAsString() : "";
        if (!tag.equals(expectedTag)) {
            throw new IOException("Browser engine version " + tag + " does not match the pinned " + expectedTag);
        }
        if (!platform.equals(expectedPlatform)) {
            throw new IOException("Browser engine was built for " + platform + ", not " + expectedPlatform);
        }
        if (!Files.isRegularFile(directory.resolve(nativeLibrary))) {
            throw new IOException("Browser engine native library " + nativeLibrary + " is missing");
        }
    }
}
