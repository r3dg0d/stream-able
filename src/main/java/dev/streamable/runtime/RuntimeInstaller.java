package dev.streamable.runtime;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Turns a verified download into an installed directory, atomically.
 *
 * <pre>
 *   archive (verified) -> &lt;parent&gt;/.staging-&lt;uuid&gt;/   extract or copy
 *                      -> post-install hook             (e.g. write install.lock)
 *                      -> receipt                        (only now "installed")
 *                      -> rename to &lt;target&gt;              (atomic on the same filesystem)
 * </pre>
 *
 * <p>The staging directory is a sibling of the target, so the final rename never
 * crosses a filesystem boundary. An existing target is first renamed aside and
 * deleted after the swap, which means a reader either sees the complete old
 * install or the complete new one - never a mix.</p>
 */
public final class RuntimeInstaller {

    /** Work done on the staged tree before it is published. */
    @FunctionalInterface
    public interface PostInstall {
        void apply(Path stagedDirectory) throws IOException;
    }

    private final ArchiveExtractor extractor;

    public RuntimeInstaller() {
        this(new ArchiveExtractor());
    }

    public RuntimeInstaller(ArchiveExtractor extractor) {
        this.extractor = extractor;
    }

    public Path install(RuntimeDescriptor descriptor, RuntimeArtifact artifact, Path verifiedFile, Path target,
                        PostInstall postInstall, BooleanSupplier cancelled) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path staging = parent.resolve(".staging-" + descriptor.id() + "-" + UUID.randomUUID());
        try {
            Files.createDirectories(staging);
            if (artifact.format() == RuntimeArtifact.Format.FILE) {
                Files.copy(verifiedFile, staging.resolve(artifact.fileName()), StandardCopyOption.REPLACE_EXISTING);
            } else {
                extractor.extract(verifiedFile, artifact, staging, cancelled);
            }
            if (postInstall != null) {
                postInstall.apply(staging);
            }
            new InstallReceipt(descriptor.id(), descriptor.version(), artifact.platform().id(),
                    artifact.sha256(), System.currentTimeMillis()).write(staging);
            publish(staging, target);
            return target;
        } finally {
            if (Files.exists(staging)) {
                deleteRecursively(staging);
            }
        }
    }

    private static void publish(Path staging, Path target) throws IOException {
        Path displaced = null;
        if (Files.exists(target)) {
            displaced = target.resolveSibling(".old-" + target.getFileName() + "-" + UUID.randomUUID());
            move(target, displaced);
        }
        try {
            move(staging, target);
        } catch (IOException e) {
            if (displaced != null && !Files.exists(target)) {
                move(displaced, target);   // put the previous install back
            }
            throw e;
        }
        if (displaced != null) {
            deleteRecursively(displaced);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    /** Deletes a tree without following symbolic links out of it. */
    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
