package dev.streamable.runtime;

import dev.streamable.StreamAbleLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registry of managed runtimes and owner of the shared runtime executor.
 *
 * <p>Deliberately thin: it wires collaborators together and lists components
 * for the UI. Each {@link ManagedRuntime} owns its own lifecycle.</p>
 */
public final class RuntimeManager implements AutoCloseable {

    private final RuntimeContext context;
    private final Map<String, ManagedRuntime> runtimes = new LinkedHashMap<>();

    private RuntimeManager(RuntimeContext context) {
        this.context = context;
    }

    /** Production wiring: bundled manifest, HTTPS, two background threads. */
    public static RuntimeManager create(Path gameDirectory, String modVersion) {
        ExecutorService executor = Executors.newFixedThreadPool(2,
                namedDaemonThreads("stream-able-runtime", nativeStackBytes()));
        RuntimeContext context = new RuntimeContext(
                gameDirectory.resolve("stream-able").resolve("runtime"),
                RuntimePlatform.current(),
                RuntimeManifest.loadBundled(),
                new RuntimeDownloader(HttpFetcher.https("Stream-able/" + modVersion + " (runtime manager)")),
                new RuntimeInstaller(),
                executor);
        return new RuntimeManager(context);
    }

    public static RuntimeManager create(RuntimeContext context) {
        return new RuntimeManager(context);
    }

    public RuntimeContext context() {
        return context;
    }

    public <T extends ManagedRuntime> T register(T runtime) {
        runtimes.put(runtime.id(), runtime);
        return runtime;
    }

    public Optional<ManagedRuntime> get(String id) {
        return Optional.ofNullable(runtimes.get(id));
    }

    public List<ManagedRuntime> all() {
        return List.copyOf(runtimes.values());
    }

    /** Reads on-disk state for every component, off the render thread. */
    public void refreshAllAsync() {
        context.executor().execute(() -> {
            for (ManagedRuntime runtime : runtimes.values()) {
                try {
                    runtime.refreshFromDisk();
                } catch (RuntimeException e) {
                    StreamAbleLog.CORE.debug("Runtime refresh failed for {}", runtime.id(), e);
                }
            }
        });
    }

    public static ThreadFactory namedDaemonThreads(String prefix) {
        return namedDaemonThreads(prefix, 0);
    }

    /**
     * @param stackBytes thread stack size, or {@code 0} for the JVM default
     */
    public static ThreadFactory namedDaemonThreads(String prefix, long stackBytes) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(null, runnable, prefix + "-" + counter.incrementAndGet(), stackBytes);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
    }

    /**
     * Stack size for threads that bring up native runtimes.
     *
     * <p>ONNX Runtime matches a {@code std::regex} against
     * {@code /proc/self/cmdline} while creating its environment, and
     * libstdc++'s regex executor recurses about three frames (~260 bytes of
     * stack) per character. Launchers put the whole classpath on the command
     * line - 11.5 KB under Prism with a large modpack - which overflows the
     * default 1 MB Java stack inside native code and kills the game with
     * SIGSEGV instead of an exception. The stack is sized from the actual
     * command line with a wide margin; it is only reserved address space
     * until used.</p>
     */
    public static long nativeStackBytes() {
        long commandLine = 0;
        try {
            commandLine = Files.readAllBytes(Path.of("/proc/self/cmdline")).length;
        } catch (IOException | RuntimeException e) {
            // Not Linux: no such file, and no known deep recursion either.
        }
        long wanted = commandLine * 1024 + (16L << 20);
        return Math.clamp(wanted, 64L << 20, 1L << 30);
    }

    @Override
    public void close() {
        runtimes.values().forEach(ManagedRuntime::cancel);
        context.executor().shutdownNow();
        try {
            context.executor().awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
