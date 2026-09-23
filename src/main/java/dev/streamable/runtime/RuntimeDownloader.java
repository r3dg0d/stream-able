package dev.streamable.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;

/**
 * Downloads a pinned artifact and proves it is the expected file.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li>Bytes are written to {@code <name>.part} and only renamed to the final
 *       name after the SHA-256 (and size, when pinned) matched. A file with the
 *       final name is therefore always a verified file.</li>
 *   <li>An interrupted download is resumed with an HTTP range request the next
 *       time, if the server honours it; otherwise it restarts cleanly.</li>
 *   <li>Every mirror is tried, each with a bounded number of attempts and an
 *       exponential backoff, before the download is reported as failed.</li>
 *   <li>A partial file that has grown past the pinned size, or that fails
 *       verification, is deleted rather than resumed forever.</li>
 * </ul>
 */
public final class RuntimeDownloader {

    /** Receives byte-level progress. Called from the download thread. */
    @FunctionalInterface
    public interface ProgressSink {
        void onProgress(long bytesDone, long bytesTotal);
    }

    /** Thrown when the user cancelled; never retried. */
    public static final class CancelledException extends IOException {
        public CancelledException() {
            super("Download cancelled");
        }
    }

    /** Thrown when the bytes did not match the pin; retried from scratch once per mirror. */
    public static final class VerificationException extends IOException {
        public VerificationException(String message) {
            super(message);
        }
    }

    private static final int BUFFER_SIZE = 1 << 16;

    private final HttpFetcher fetcher;
    private final int attemptsPerMirror;
    private final long initialBackoffMillis;
    private final Sleeper sleeper;

    /** Injected so tests do not actually wait out the backoff. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public RuntimeDownloader(HttpFetcher fetcher) {
        this(fetcher, 3, 1_000, Thread::sleep);
    }

    public RuntimeDownloader(HttpFetcher fetcher, int attemptsPerMirror, long initialBackoffMillis, Sleeper sleeper) {
        this.fetcher = fetcher;
        this.attemptsPerMirror = Math.max(1, attemptsPerMirror);
        this.initialBackoffMillis = Math.max(0, initialBackoffMillis);
        this.sleeper = sleeper;
    }

    /**
     * Downloads {@code artifact} into {@code directory}, returning the verified file.
     *
     * <p>If a verified file with the final name already exists it is re-hashed
     * and returned without touching the network.</p>
     */
    public Path download(RuntimeArtifact artifact, Path directory, ProgressSink progress,
                         BooleanSupplier cancelled) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(artifact.fileName());
        Path partial = directory.resolve(artifact.fileName() + ".part");

        if (Files.isRegularFile(target)) {
            if (Sha256.matches(artifact.sha256(), Sha256.hash(target))) {
                long size = Files.size(target);
                progress.onProgress(size, size);
                return target;
            }
            // Present but wrong: never trust it, never execute it.
            Files.deleteIfExists(target);
        }

        IOException lastFailure = null;
        for (URI url : artifact.urls()) {
            long backoff = initialBackoffMillis;
            for (int attempt = 1; attempt <= attemptsPerMirror; attempt++) {
                checkCancelled(cancelled);
                try {
                    fetchInto(url, artifact, partial, progress, cancelled);
                    verify(artifact, partial);
                    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    return target;
                } catch (CancelledException e) {
                    throw e;
                } catch (VerificationException e) {
                    // Corrupt or tampered bytes: resuming them would be pointless.
                    Files.deleteIfExists(partial);
                    lastFailure = e;
                } catch (IOException e) {
                    lastFailure = e;
                }
                if (attempt < attemptsPerMirror) {
                    try {
                        sleeper.sleep(backoff);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CancelledException();
                    }
                    backoff = Math.min(backoff * 2, 30_000);
                }
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("No mirror could be reached");
    }

    private void fetchInto(URI url, RuntimeArtifact artifact, Path partial, ProgressSink progress,
                           BooleanSupplier cancelled) throws IOException {
        long existing = Files.isRegularFile(partial) ? Files.size(partial) : 0;
        if (artifact.size() > 0 && existing > artifact.size()) {
            Files.deleteIfExists(partial);
            existing = 0;
        }
        if (artifact.size() > 0 && existing == artifact.size()) {
            return;   // fully downloaded previously; verification decides
        }

        try (HttpFetcher.Response response = fetcher.open(url, existing)) {
            if (!response.isOk()) {
                if (response.status() == 416 && existing > 0) {
                    // Range not satisfiable: the partial is already complete or
                    // bogus. Verification sorts out which.
                    return;
                }
                throw new IOException("HTTP " + response.status() + " from " + url.getHost());
            }
            boolean append = existing > 0 && response.isPartial();
            if (!append) {
                existing = 0;   // server ignored the range request: start over
            }
            long total = artifact.size() > 0 ? artifact.size()
                    : (response.contentLength() > 0 ? existing + response.contentLength() : -1);

            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(partial, StandardOpenOption.CREATE,
                         append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long done = existing;
                long lastReport = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    checkCancelled(cancelled);
                    out.write(buffer, 0, read);
                    done += read;
                    if (artifact.size() > 0 && done > artifact.size()) {
                        throw new VerificationException("Download is larger than the pinned size");
                    }
                    if (done - lastReport >= 256 * 1024) {
                        progress.onProgress(done, total);
                        lastReport = done;
                    }
                }
                progress.onProgress(done, total);
            }
        }
    }

    private static void verify(RuntimeArtifact artifact, Path partial) throws IOException {
        long size = Files.size(partial);
        if (artifact.size() > 0 && size != artifact.size()) {
            throw new IOException("Download incomplete: " + size + " of " + artifact.size() + " bytes");
        }
        MessageDigest digest = Sha256.newDigest();
        try (InputStream in = Files.newInputStream(partial)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        String actual = Sha256.toHex(digest.digest());
        if (!Sha256.matches(artifact.sha256(), actual)) {
            throw new VerificationException("SHA-256 mismatch for " + artifact.fileName()
                    + ": expected " + artifact.sha256() + ", got " + actual);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws CancelledException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }
}
