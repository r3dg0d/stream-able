package dev.streamable.runtime;

import dev.streamable.StreamAbleLog;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One independently managed runtime component (FFmpeg, the browser engine,
 * ONNX Runtime, a model file).
 *
 * <p>Each component owns its own lifecycle, so a failed Chromium download never
 * blocks FFmpeg and a missing AI model never blocks recording. All heavy work -
 * hashing, downloading, extracting, native initialisation - runs on the shared
 * runtime executor; the render thread only ever reads the immutable
 * {@link #progress()} snapshot.</p>
 *
 * <p>Subclasses decide where the component lives ({@link #installDirectory()}),
 * what "usable" means ({@link #validateInstall(Path)}) and how it is brought up
 * ({@link #initialize(Path)}).</p>
 */
public abstract class ManagedRuntime {

    protected final RuntimeContext context;
    protected final RuntimeDescriptor descriptor;

    private final CopyOnWriteArrayList<Consumer<RuntimeProgress>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private volatile RuntimeProgress progress = RuntimeProgress.of(RuntimeState.NOT_INSTALLED, "");
    private volatile Path activeDirectory;
    private CompletableFuture<Path> job;

    protected ManagedRuntime(RuntimeContext context, RuntimeDescriptor descriptor) {
        this.context = context;
        this.descriptor = descriptor;
        if (artifact().isEmpty()) {
            progress = RuntimeProgress.of(RuntimeState.UNSUPPORTED,
                    descriptor.displayName() + " is not available for " + context.platform().displayName() + ".");
        }
    }

    // ---- identity ------------------------------------------------------------

    public String id() {
        return descriptor.id();
    }

    public String displayName() {
        return descriptor.displayName();
    }

    public String version() {
        return descriptor.version();
    }

    public RuntimeDescriptor descriptor() {
        return descriptor;
    }

    public Optional<RuntimeArtifact> artifact() {
        return descriptor.artifactFor(context.platform());
    }

    public RuntimeProgress progress() {
        return progress;
    }

    public RuntimeState state() {
        return progress.state();
    }

    public boolean isReady() {
        return progress.state() == RuntimeState.READY;
    }

    /** The directory currently in use, once {@link #isReady()}. */
    public Optional<Path> activeDirectory() {
        return Optional.ofNullable(activeDirectory);
    }

    public void addListener(Consumer<RuntimeProgress> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<RuntimeProgress> listener) {
        listeners.remove(listener);
    }

    // ---- layout --------------------------------------------------------------

    /** Parent of every version of this component. */
    protected Path componentDirectory() {
        return context.root().resolve(descriptor.id());
    }

    /** Where the pinned version is installed. */
    public Path installDirectory() {
        return componentDirectory().resolve(descriptor.version());
    }

    /** Checks the installed tree is complete and usable, beyond the receipt. */
    protected void validateInstall(Path directory) throws IOException {
    }

    /** Brings the runtime up after installation (load natives, run a self-test). */
    protected void initialize(Path directory) throws Exception {
    }

    /** Adjusts the staged tree before it is published (e.g. marker files). */
    protected void postInstall(Path stagedDirectory) throws IOException {
    }

    /** Whether the pinned version is installed, by receipt only. Cheap; no hashing. */
    public boolean isInstalled() {
        Optional<RuntimeArtifact> artifact = artifact();
        if (artifact.isEmpty()) {
            return false;
        }
        return InstallReceipt.read(installDirectory())
                .filter(receipt -> receipt.matches(descriptor, artifact.get()))
                .isPresent();
    }

    /**
     * Older installs of this component written by an earlier Stream-able
     * version, newest first. They remain usable until the upgrade lands.
     */
    public List<Path> previousInstalls() {
        List<Path> found = new ArrayList<>();
        Path parent = componentDirectory();
        if (!Files.isDirectory(parent)) {
            return found;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(parent)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (name.startsWith(".") || name.equals(descriptor.version()) || !Files.isDirectory(child)) {
                    continue;
                }
                InstallReceipt.read(child)
                        .filter(receipt -> receipt.component().equals(descriptor.id())
                                && receipt.platform().equals(context.platform().id()))
                        .ifPresent(receipt -> found.add(child));
            }
        } catch (IOException e) {
            StreamAbleLog.CORE.debug("Could not scan {} for previous installs: {}", parent, e.toString());
        }
        found.sort(Comparator.comparingLong((Path p) -> InstallReceipt.read(p).map(InstallReceipt::installedAt).orElse(0L))
                .reversed());
        return found;
    }

    /**
     * Updates the state from what is on disk, without any network access.
     * Safe to call from a background thread at startup.
     */
    public RuntimeState refreshFromDisk() {
        if (artifact().isEmpty()) {
            return state();
        }
        if (state().isBusy() || state() == RuntimeState.READY) {
            return state();
        }
        if (isInstalled()) {
            publish(RuntimeProgress.of(RuntimeState.NOT_INSTALLED, "Installed; not started yet."));
        } else if (!previousInstalls().isEmpty()) {
            publish(RuntimeProgress.of(RuntimeState.UPDATE_AVAILABLE,
                    "An older version is installed; version " + descriptor.version() + " is available."));
        } else {
            publish(RuntimeProgress.of(RuntimeState.NOT_INSTALLED, "Not installed."));
        }
        return state();
    }

    // ---- lifecycle -------------------------------------------------------------

    /**
     * Installs if needed, then initialises. Returns the running job if one is
     * already in flight, so repeated clicks never start parallel downloads.
     */
    public synchronized CompletableFuture<Path> ensureReady() {
        if (job != null && !job.isDone()) {
            return job;
        }
        if (isReady() && activeDirectory != null) {
            return CompletableFuture.completedFuture(activeDirectory);
        }
        Optional<RuntimeArtifact> artifact = artifact();
        if (artifact.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(progress.detail()));
        }
        cancelRequested.set(false);
        job = CompletableFuture.supplyAsync(() -> run(artifact.get()), context.executor());
        return job;
    }

    /** Requests cancellation of an in-flight download or extraction. */
    public void cancel() {
        cancelRequested.set(true);
    }

    /** Marks the runtime unusable after a failure detected outside the install flow. */
    public void markFailed(String reason) {
        activeDirectory = null;
        publish(RuntimeProgress.of(RuntimeState.FAILED, reason));
    }

    private Path run(RuntimeArtifact artifact) {
        try {
            publish(RuntimeProgress.of(RuntimeState.CHECKING, "Checking the installed files."));
            Path directory = installDirectory();
            if (!isInstalled() || !isValid(directory)) {
                directory = install(artifact);
            }
            publish(RuntimeProgress.of(RuntimeState.INITIALIZING, "Starting " + descriptor.displayName() + "."));
            initialize(directory);
            activeDirectory = directory;
            publish(RuntimeProgress.of(RuntimeState.READY, descriptor.displayName() + " " + descriptor.version() + " is ready."));
            cleanupPreviousInstalls();
            return directory;
        } catch (RuntimeDownloader.CancelledException e) {
            publish(RuntimeProgress.of(RuntimeState.NOT_INSTALLED, "Cancelled."));
            throw new java.util.concurrent.CancellationException("Cancelled");
        } catch (Throwable t) {
            String reason = describe(t);
            StreamAbleLog.CORE.warn("Runtime '{}' failed: {}", descriptor.id(), reason, t);
            activeDirectory = null;
            publish(RuntimeProgress.of(RuntimeState.FAILED, reason));
            throw new java.util.concurrent.CompletionException(t);
        }
    }

    private boolean isValid(Path directory) {
        try {
            validateInstall(directory);
            return true;
        } catch (IOException e) {
            StreamAbleLog.CORE.info("Installed '{}' failed validation ({}); reinstalling.", descriptor.id(), e.getMessage());
            return false;
        }
    }

    private Path install(RuntimeArtifact artifact) throws IOException {
        Path downloads = context.downloads();
        publish(RuntimeProgress.downloading(0, artifact.size(), "Downloading " + descriptor.displayName() + "."));
        Path file = context.downloader().download(artifact, downloads,
                (done, total) -> publish(RuntimeProgress.downloading(done, total,
                        "Downloading " + descriptor.displayName() + ".")),
                cancelRequested::get);
        publish(RuntimeProgress.of(RuntimeState.VERIFYING, "Verified SHA-256 " + artifact.sha256().substring(0, 12) + "..."));
        publish(RuntimeProgress.of(artifact.format() == RuntimeArtifact.Format.FILE
                ? RuntimeState.INSTALLING : RuntimeState.EXTRACTING, "Unpacking " + descriptor.displayName() + "."));
        Path target = installDirectory();
        context.installer().install(descriptor, artifact, file, target, this::postInstall, cancelRequested::get);
        publish(RuntimeProgress.of(RuntimeState.INSTALLING, "Installed to " + target.getFileName() + "."));
        validateInstall(target);
        // The archive is not needed once its contents are verified and in place.
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            StreamAbleLog.CORE.debug("Could not remove downloaded archive {}: {}", file, e.toString());
        }
        return target;
    }

    /** Removes superseded versions once the pinned one is running. */
    private void cleanupPreviousInstalls() {
        for (Path old : previousInstalls()) {
            try {
                RuntimeInstaller.deleteRecursively(old);
                StreamAbleLog.CORE.info("Removed superseded runtime {}", old);
            } catch (IOException e) {
                StreamAbleLog.CORE.debug("Could not remove old runtime {}: {}", old, e.toString());
            }
        }
    }

    protected void publish(RuntimeProgress next) {
        progress = next;
        for (Consumer<RuntimeProgress> listener : listeners) {
            try {
                listener.accept(next);
            } catch (RuntimeException e) {
                StreamAbleLog.CORE.debug("Runtime progress listener failed", e);
            }
        }
    }

    private static String describe(Throwable t) {
        Throwable cause = t;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (cause instanceof java.net.UnknownHostException) {
            return "No network connection (could not resolve " + message + ").";
        }
        if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
            return "The download timed out. Check your connection and retry.";
        }
        if (cause instanceof java.nio.file.AccessDeniedException) {
            return "Permission denied writing " + message + ".";
        }
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
