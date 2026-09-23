package dev.streamable.runtime;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks verified runtime archives - defensively.
 *
 * <p>Even a pinned, checksummed archive is treated as untrusted input to the
 * extractor, because the extractor is the piece that decides where bytes land
 * on disk. Every entry is checked before anything is written:</p>
 * <ul>
 *   <li>absolute paths, drive letters and UNC prefixes are rejected;</li>
 *   <li>{@code ..} segments are rejected outright, and the normalised target
 *       must still be inside the destination (zip-slip / tar-slip);</li>
 *   <li>hard links and device nodes are rejected; symbolic links are only
 *       accepted when relative and resolving inside the destination;</li>
 *   <li>entry counts and the total expanded size are capped, so a decompression
 *       bomb fails instead of filling the disk.</li>
 * </ul>
 *
 * <p>The tar reader is implemented here rather than pulled in as a dependency:
 * it only needs to understand ustar, GNU long names and PAX {@code path}
 * records, and owning it means owning its security properties.</p>
 */
public final class ArchiveExtractor {

    /** Thrown for any entry that would escape or abuse the destination. */
    public static final class UnsafeEntryException extends IOException {
        public UnsafeEntryException(String message) {
            super(message);
        }
    }

    public static final long DEFAULT_MAX_BYTES = 4L * 1024 * 1024 * 1024;
    public static final int DEFAULT_MAX_ENTRIES = 100_000;
    private static final int TAR_BLOCK = 512;

    private final long maxTotalBytes;
    private final int maxEntries;

    public ArchiveExtractor() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_ENTRIES);
    }

    public ArchiveExtractor(long maxTotalBytes, int maxEntries) {
        this.maxTotalBytes = maxTotalBytes;
        this.maxEntries = maxEntries;
    }

    /** Result counters, mostly for logs and tests. */
    public record Result(int files, int directories, long bytes) {
    }

    /**
     * Extracts {@code archive} into {@code destination} according to the
     * artifact's format, strip count and include filter.
     */
    public Result extract(Path archive, RuntimeArtifact artifact, Path destination, BooleanSupplier cancelled)
            throws IOException {
        Files.createDirectories(destination);
        Path root = destination.toAbsolutePath().normalize();
        State state = new State(root, artifact, cancelled);
        switch (artifact.format()) {
            case FILE -> throw new IllegalArgumentException("FILE artifacts are not archives");
            case ZIP -> {
                try (InputStream in = new BufferedInputStream(Files.newInputStream(archive))) {
                    extractZip(in, state);
                }
            }
            case TAR_GZ -> {
                try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(archive)), 1 << 16)) {
                    extractTar(in, state);
                }
            }
            case TAR_XZ -> {
                try (InputStream in = new XZInputStream(new BufferedInputStream(Files.newInputStream(archive), 1 << 16))) {
                    extractTar(in, state);
                }
            }
            case ZIP_NESTED_TAR_GZ -> {
                try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
                    ZipEntry entry;
                    boolean found = false;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".tar.gz")) {
                            // The nested tar is streamed straight out of the zip:
                            // it never touches the disk unextracted.
                            extractTar(new GZIPInputStream(nonClosing(zip), 1 << 16), state);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        throw new IOException("Archive contains no .tar.gz payload");
                    }
                }
            }
        }
        return new Result(state.files, state.directories, state.bytes);
    }

    // ---- zip -----------------------------------------------------------------

    private void extractZip(InputStream raw, State state) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    state.directory(name);
                } else {
                    state.file(name, zip, -1, false);
                }
            }
        }
    }

    // ---- tar -----------------------------------------------------------------

    private void extractTar(InputStream in, State state) throws IOException {
        byte[] header = new byte[TAR_BLOCK];
        String pendingLongName = null;
        String pendingPaxPath = null;
        int zeroBlocks = 0;
        while (true) {
            if (!readFully(in, header)) {
                return;   // tolerate archives without the trailing zero blocks
            }
            if (isZeroBlock(header)) {
                if (++zeroBlocks >= 2) {
                    return;
                }
                continue;
            }
            zeroBlocks = 0;
            if (!checksumValid(header)) {
                throw new IOException("Corrupt tar header (bad checksum)");
            }
            char type = (char) header[156];
            long size = parseOctal(header, 124, 12);
            if (size < 0) {
                throw new IOException("Corrupt tar header (bad size)");
            }
            int mode = (int) Math.max(0, parseOctal(header, 100, 8));
            String name = headerName(header);

            switch (type) {
                case 'L' -> {                          // GNU long name for the next entry
                    pendingLongName = readString(in, size);
                    continue;
                }
                case 'K' -> {                          // GNU long link name: links are refused anyway
                    skip(in, padded(size));
                    continue;
                }
                case 'x' -> {                          // PAX extended header for the next entry
                    pendingPaxPath = paxPath(readString(in, size));
                    continue;
                }
                case 'g' -> {                          // PAX global header: nothing we use
                    skip(in, padded(size));
                    continue;
                }
                default -> {
                }
            }
            if (pendingPaxPath != null) {
                name = pendingPaxPath;
            } else if (pendingLongName != null) {
                name = pendingLongName;
            }
            pendingPaxPath = null;
            pendingLongName = null;

            switch (type) {
                case '0', '\0', '7' -> {
                    BoundedStream body = new BoundedStream(in, size);
                    state.file(name, body, mode, (mode & 0111) != 0);
                    body.drain();
                    skip(in, padded(size) - size);
                }
                case '5' -> {
                    state.directory(name);
                    skip(in, padded(size));
                }
                case '2' -> {
                    String target = cString(header, 157, 100);
                    state.symlink(name, target);
                    skip(in, padded(size));
                }
                case '1' -> throw new UnsafeEntryException("Hard links are not allowed in runtime archives: " + name);
                case '3', '4', '6' -> throw new UnsafeEntryException("Device and FIFO entries are not allowed: " + name);
                default -> skip(in, padded(size));     // unknown vendor types carry no files we need
            }
        }
    }

    private static String headerName(byte[] header) {
        String name = cString(header, 0, 100);
        // ustar splits long paths into prefix/name.
        String magic = new String(header, 257, 5, StandardCharsets.US_ASCII);
        if (magic.equals("ustar")) {
            String prefix = cString(header, 345, 155);
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
        }
        return name;
    }

    private static String paxPath(String records) {
        // Records: "<len> key=value\n"
        int index = 0;
        String path = null;
        while (index < records.length()) {
            int space = records.indexOf(' ', index);
            if (space < 0) {
                break;
            }
            int length;
            try {
                length = Integer.parseInt(records.substring(index, space));
            } catch (NumberFormatException e) {
                break;
            }
            if (length <= 0 || index + length > records.length()) {
                break;
            }
            String record = records.substring(space + 1, index + length - 1);
            if (record.startsWith("path=")) {
                path = record.substring(5);
            }
            index += length;
        }
        return path;
    }

    private static boolean checksumValid(byte[] header) {
        long stored = parseOctal(header, 148, 8);
        long unsigned = 0;
        for (int i = 0; i < TAR_BLOCK; i++) {
            unsigned += (i >= 148 && i < 156) ? ' ' : (header[i] & 0xFF);
        }
        return stored == unsigned;
    }

    private static long parseOctal(byte[] data, int offset, int length) {
        // GNU base-256 encoding for large values.
        if ((data[offset] & 0x80) != 0) {
            long value = data[offset] & 0x7F;
            for (int i = 1; i < length; i++) {
                value = (value << 8) | (data[offset + i] & 0xFF);
                if (value < 0) {
                    return -1;
                }
            }
            return value;
        }
        long value = 0;
        boolean seenDigit = false;
        for (int i = offset; i < offset + length; i++) {
            byte b = data[i];
            if (b == 0 || (b == ' ' && seenDigit)) {
                break;
            }
            if (b == ' ') {
                continue;
            }
            if (b < '0' || b > '7') {
                return -1;
            }
            seenDigit = true;
            value = (value << 3) + (b - '0');
        }
        return value;
    }

    private static String cString(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static long padded(long size) {
        return (size + TAR_BLOCK - 1) / TAR_BLOCK * TAR_BLOCK;
    }

    private static String readString(InputStream in, long size) throws IOException {
        if (size > 1 << 20) {
            throw new IOException("Tar metadata record too large");
        }
        byte[] data = new byte[(int) size];
        if (!readFully(in, data)) {
            throw new EOFException("Truncated tar metadata");
        }
        skip(in, padded(size) - size);
        String text = new String(data, StandardCharsets.UTF_8);
        int nul = text.indexOf('\0');
        return nul >= 0 ? text.substring(0, nul) : text;
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                if (offset == 0) {
                    return false;
                }
                throw new EOFException("Truncated archive");
            }
            offset += read;
        }
        return true;
    }

    private static void skip(InputStream in, long count) throws IOException {
        long remaining = count;
        byte[] scratch = null;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (scratch == null) {
                    scratch = new byte[8192];
                }
                int read = in.read(scratch, 0, (int) Math.min(scratch.length, remaining));
                if (read < 0) {
                    throw new EOFException("Truncated archive");
                }
                skipped = read;
            }
            remaining -= skipped;
        }
    }

    private static InputStream nonClosing(InputStream in) {
        return new java.io.FilterInputStream(in) {
            @Override
            public void close() {
                // The outer zip stream owns the underlying file.
            }
        };
    }

    // ---- shared entry handling -------------------------------------------------

    /** Validates an archive-relative path; returns the stripped, normalised form or {@code null} to skip. */
    static String sanitise(String rawName, int stripComponents) throws UnsafeEntryException {
        if (rawName == null || rawName.isEmpty()) {
            return null;
        }
        if (rawName.indexOf('\0') >= 0) {
            throw new UnsafeEntryException("Entry name contains NUL");
        }
        String name = rawName.replace('\\', '/');
        if (name.startsWith("/") || name.startsWith("//") || (name.length() > 1 && name.charAt(1) == ':')) {
            throw new UnsafeEntryException("Absolute path in archive: " + rawName);
        }
        String[] segments = name.split("/");
        StringBuilder clean = new StringBuilder();
        int kept = 0;
        int skippedForStrip = 0;
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new UnsafeEntryException("Parent-directory traversal in archive: " + rawName);
            }
            if (skippedForStrip < stripComponents) {
                skippedForStrip++;
                continue;
            }
            if (kept++ > 0) {
                clean.append('/');
            }
            clean.append(segment);
        }
        return clean.isEmpty() ? null : clean.toString();
    }

    private final class State {
        private final Path root;
        private final RuntimeArtifact artifact;
        private final BooleanSupplier cancelled;
        private int entries;
        private int files;
        private int directories;
        private long bytes;

        State(Path root, RuntimeArtifact artifact, BooleanSupplier cancelled) {
            this.root = root;
            this.artifact = artifact;
            this.cancelled = cancelled;
        }

        private Path resolve(String relative) throws UnsafeEntryException {
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root) || target.equals(root)) {
                throw new UnsafeEntryException("Entry escapes the install directory: " + relative);
            }
            return target;
        }

        private void count() throws IOException {
            if (cancelled.getAsBoolean()) {
                throw new RuntimeDownloader.CancelledException();
            }
            if (++entries > maxEntries) {
                throw new IOException("Archive has too many entries");
            }
        }

        void directory(String rawName) throws IOException {
            count();
            String relative = sanitise(rawName, artifact.stripComponents());
            if (relative == null || !artifact.include().isEmpty()) {
                // With an include filter, directories are created on demand by
                // the files that survive it, so filtered-out trees leave nothing.
                return;
            }
            Path target = resolve(relative);
            ensureNoSymlinkParents(target);
            Files.createDirectories(target);
            directories++;
        }

        void file(String rawName, InputStream body, int mode, boolean executableInArchive) throws IOException {
            count();
            String relative = sanitise(rawName, artifact.stripComponents());
            if (relative == null || !artifact.shouldInclude(relative)) {
                return;
            }
            Path target = resolve(relative);
            ensureNoSymlinkParents(target);
            Files.createDirectories(target.getParent());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target) || Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new UnsafeEntryException("Entry would overwrite a link or directory: " + relative);
                }
            }
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = body.read(buffer)) > 0) {
                    bytes += read;
                    if (bytes > maxTotalBytes) {
                        throw new IOException("Archive expands beyond the " + (maxTotalBytes >> 20) + " MB limit");
                    }
                    out.write(buffer, 0, read);
                }
            }
            if (executableInArchive || artifact.isExecutable(relative)) {
                markExecutable(target);
            }
            files++;
        }

        void symlink(String rawName, String linkTarget) throws IOException {
            count();
            String relative = sanitise(rawName, artifact.stripComponents());
            if (relative == null || !artifact.shouldInclude(relative)) {
                return;
            }
            if (linkTarget == null || linkTarget.isEmpty() || linkTarget.startsWith("/")
                    || linkTarget.contains("\\") || (linkTarget.length() > 1 && linkTarget.charAt(1) == ':')) {
                throw new UnsafeEntryException("Unsafe symbolic link " + relative + " -> " + linkTarget);
            }
            Path link = resolve(relative);
            Path resolvedTarget = link.getParent().resolve(linkTarget).normalize();
            if (!resolvedTarget.startsWith(root)) {
                throw new UnsafeEntryException("Symbolic link escapes the install directory: "
                        + relative + " -> " + linkTarget);
            }
            ensureNoSymlinkParents(link);
            Files.createDirectories(link.getParent());
            Files.deleteIfExists(link);
            try {
                Files.createSymbolicLink(link, Path.of(linkTarget));
            } catch (UnsupportedOperationException | SecurityException e) {
                throw new IOException("Cannot create symbolic link on this filesystem: " + relative, e);
            }
        }

        /** Refuses to write through a symlinked parent directory created by an earlier entry. */
        private void ensureNoSymlinkParents(Path target) throws UnsafeEntryException {
            for (Path current = target.getParent(); current != null && current.startsWith(root) && !current.equals(root);
                 current = current.getParent()) {
                if (Files.isSymbolicLink(current)) {
                    throw new UnsafeEntryException("Entry would be written through a symbolic link: " + target);
                }
            }
        }
    }

    static void markExecutable(Path file) {
        try {
            if (Files.getFileStore(file).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class)) {
                Set<PosixFilePermission> permissions = EnumSet.copyOf(Files.getPosixFilePermissions(file));
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
                permissions.add(PosixFilePermission.GROUP_EXECUTE);
                permissions.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(file, permissions);
            } else {
                file.toFile().setExecutable(true, false);
            }
        } catch (IOException | UnsupportedOperationException e) {
            file.toFile().setExecutable(true, false);
        }
    }

    /** Exposes at most {@code limit} bytes of the underlying tar stream. */
    private static final class BoundedStream extends InputStream {
        private final InputStream in;
        private long remaining;

        BoundedStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = in.read();
            if (b < 0) {
                throw new EOFException("Truncated archive entry");
            }
            remaining--;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (read < 0) {
                throw new EOFException("Truncated archive entry");
            }
            remaining -= read;
            return read;
        }

        /** Consumes whatever the consumer did not read, keeping the tar stream aligned. */
        void drain() throws IOException {
            ArchiveExtractor.skip(in, remaining);
            remaining = 0;
        }

        @Override
        public void close() {
            // Never close the shared tar stream.
        }
    }
}
