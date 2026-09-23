package dev.streamable.runtime;

import dev.streamable.StreamAbleLog;

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
        ExecutorService executor = Executors.newFixedThreadPool(2, namedDaemonThreads("stream-able-runtime"));
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
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
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
