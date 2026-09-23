package dev.streamable.audio.ai;

import dev.streamable.runtime.ManagedRuntime;
import dev.streamable.runtime.RuntimeContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ONNX Runtime (CPU) as a managed runtime, used only for local microphone
 * noise cancellation.
 *
 * <p>The pinned Maven Central jar is verified by SHA-256 like every runtime;
 * on install the native libraries for this platform are extracted next to it
 * (so ONNX Runtime does not unpack them into a temp directory on every start)
 * and the jar is then loaded in an {@link IsolatedModelLoader}. No Python,
 * PyTorch, CUDA or system install is involved.</p>
 */
public final class AudioInferenceRuntime extends ManagedRuntime {

    public static final String ID = "onnxruntime";

    private volatile InferenceEngine engine;
    private volatile IsolatedModelLoader loader;

    public AudioInferenceRuntime(RuntimeContext context) {
        super(context, context.manifest().require(ID));
    }

    private Path jar(Path directory) {
        return directory.resolve(artifact().orElseThrow().fileName());
    }

    /** ONNX Runtime's own name for this platform's native directory. */
    String nativeFolder() {
        return switch (context.platform().id()) {
            case "linux-x86_64" -> "linux-x64";
            case "linux-aarch64" -> "linux-aarch64";
            case "windows-x86_64" -> "win-x64";
            case "macos-x86_64" -> "osx-x64";
            case "macos-aarch64" -> "osx-aarch64";
            default -> context.platform().id();
        };
    }

    @Override
    protected void postInstall(Path staged) throws IOException {
        Path natives = staged.resolve("native");
        Files.createDirectories(natives);
        String prefix = "ai/onnxruntime/native/" + nativeFolder() + "/";
        int extracted = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar(staged)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix)) {
                    continue;
                }
                String file = name.substring(prefix.length());
                String lower = file.toLowerCase(Locale.ROOT);
                // Flat file names only; debug symbols are skipped.
                if (file.contains("/") || file.contains("\\") || file.contains("..")
                        || lower.endsWith(".pdb") || lower.contains(".dsym")) {
                    continue;
                }
                // Copy the current entry without closing the shared zip stream.
                InputStream entryStream = new java.io.FilterInputStream(zip) {
                    @Override
                    public void close() {
                    }
                };
                Files.copy(entryStream, natives.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                extracted++;
            }
        }
        if (extracted == 0) {
            throw new IOException("ONNX Runtime has no native library for " + nativeFolder());
        }
    }

    @Override
    protected void validateInstall(Path directory) throws IOException {
        if (!Files.isRegularFile(jar(directory))) {
            throw new IOException("ONNX Runtime jar missing");
        }
        Path natives = directory.resolve("native");
        boolean found = false;
        if (Files.isDirectory(natives)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(natives)) {
                for (Path file : files) {
                    if (file.getFileName().toString().contains("onnxruntime4j_jni")) {
                        found = true;
                    }
                }
            }
        }
        if (!found) {
            throw new IOException("ONNX Runtime native libraries missing");
        }
    }

    @Override
    protected synchronized void initialize(Path directory) throws Exception {
        if (engine != null) {
            return;
        }
        // Must be set before the first OnnxRuntime class initialises.
        System.setProperty("onnxruntime.native.path", directory.resolve("native").toAbsolutePath().toString());
        IsolatedModelLoader isolated = new IsolatedModelLoader(jar(directory), AudioInferenceRuntime.class.getClassLoader());
        try {
            engine = isolated.createEngine();
            loader = isolated;
        } catch (ReflectiveOperationException | LinkageError e) {
            isolated.close();
            throw new IOException("ONNX Runtime could not be loaded: " + e, e);
        }
    }

    /** The loaded engine, or {@code null} until {@link #isReady()}. */
    public InferenceEngine engine() {
        return engine;
    }
}
