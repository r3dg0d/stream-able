package dev.streamable.audio.ai;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * Class loader that joins the downloaded ONNX Runtime jar with Stream-able's
 * ONNX-facing classes, isolated from the rest of the mod.
 *
 * <ul>
 *   <li>{@code ai.onnxruntime.*} comes from the verified runtime jar only.</li>
 *   <li>{@code dev.streamable.audio.ai.ort.*} is defined here, from the mod's
 *       own class files, so it links against that runtime.</li>
 *   <li>Everything else - including the {@link InferenceEngine} and
 *       {@link SpectralModel} interfaces - is delegated to the mod's loader, so
 *       instances cross the boundary as ordinary objects.</li>
 * </ul>
 *
 * <p>This keeps a 55 MB native runtime out of the mod jar and makes a broken or
 * missing runtime fail inside this loader rather than in Minecraft's.</p>
 */
public final class IsolatedModelLoader extends URLClassLoader {

    private static final String BRIDGE_PACKAGE = "dev.streamable.audio.ai.ort.";
    private static final String RUNTIME_PACKAGE = "ai.onnxruntime.";

    static {
        registerAsParallelCapable();
    }

    private final ClassLoader modLoader;

    public IsolatedModelLoader(Path runtimeJar, ClassLoader modLoader) throws IOException {
        super("stream-able-onnxruntime", new URL[]{runtimeJar.toUri().toURL()}, modLoader);
        this.modLoader = modLoader;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (name.startsWith(BRIDGE_PACKAGE)) {
                    loaded = defineBridgeClass(name);
                } else if (name.startsWith(RUNTIME_PACKAGE)) {
                    loaded = findClass(name);
                } else {
                    return super.loadClass(name, resolve);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private Class<?> defineBridgeClass(String name) throws ClassNotFoundException {
        String resource = name.replace('.', '/') + ".class";
        try (InputStream in = modLoader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new ClassNotFoundException(name);
            }
            byte[] bytes = in.readAllBytes();
            return defineClass(name, bytes, 0, bytes.length);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    /** Instantiates the ONNX Runtime engine inside this loader. */
    public InferenceEngine createEngine() throws ReflectiveOperationException {
        Class<?> type = loadClass(BRIDGE_PACKAGE + "OrtInferenceEngine");
        return (InferenceEngine) type.getDeclaredConstructor().newInstance();
    }
}
