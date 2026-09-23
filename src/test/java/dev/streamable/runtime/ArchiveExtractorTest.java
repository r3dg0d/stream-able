package dev.streamable.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveExtractorTest {

    @TempDir
    Path dir;

    private static RuntimeArtifact artifact(RuntimeArtifact.Format format, int strip, List<String> include,
                                            List<String> executables) {
        return new RuntimeArtifact(RuntimePlatform.ANY, List.of(URI.create("https://example.org/a")),
                "0".repeat(64), -1, format, "archive", strip, include, executables);
    }

    private Path write(String name, byte[] data) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, data);
        return file;
    }

    private Path out() {
        return dir.resolve("out");
    }

    @Test
    void extractsTarGzWithStripAndIncludeFilter() throws IOException {
        byte[] tar = new TestArchives.Tar()
                .directory("pkg/")
                .directory("pkg/bin/")
                .file("pkg/bin/tool", "binary".getBytes(StandardCharsets.UTF_8), 0755)
                .file("pkg/doc/manual.html", "docs")
                .file("pkg/LICENSE", "license")
                .gzipped();
        Path archive = write("a.tar.gz", tar);
        ArchiveExtractor.Result result = new ArchiveExtractor().extract(archive,
                artifact(RuntimeArtifact.Format.TAR_GZ, 1, List.of("bin/*", "LICENSE"), List.of("bin/tool")),
                out(), () -> false);
        assertEquals(2, result.files());
        assertEquals("binary", Files.readString(out().resolve("bin/tool")));
        assertTrue(Files.exists(out().resolve("LICENSE")));
        assertFalse(Files.exists(out().resolve("doc/manual.html")), "filtered out");
        if (Files.getFileStore(out()).supportsFileAttributeView("posix")) {
            assertTrue(Files.isExecutable(out().resolve("bin/tool")));
        }
    }

    @Test
    void extractsTarXz() throws IOException {
        byte[] tar = new TestArchives.Tar().file("top/bin/ffmpeg", "x").bytes();
        ByteArrayOutputStream xz = new ByteArrayOutputStream();
        try (XZOutputStream stream = new XZOutputStream(xz, new LZMA2Options())) {
            stream.write(tar);
        }
        Path archive = write("a.tar.xz", xz.toByteArray());
        new ArchiveExtractor().extract(archive, artifact(RuntimeArtifact.Format.TAR_XZ, 1, List.of(), List.of()),
                out(), () -> false);
        assertEquals("x", Files.readString(out().resolve("bin/ffmpeg")));
    }

    @Test
    void extractsZipWithStrip() throws IOException {
        Path archive = write("a.zip", TestArchives.zip(
                "ffmpeg-9/", "",
                "ffmpeg-9/bin/ffmpeg.exe", "exe",
                "ffmpeg-9/doc/x.html", "doc"));
        new ArchiveExtractor().extract(archive,
                artifact(RuntimeArtifact.Format.ZIP, 1, List.of("bin/ffmpeg.exe"), List.of()), out(), () -> false);
        assertEquals("exe", Files.readString(out().resolve("bin/ffmpeg.exe")));
        assertFalse(Files.exists(out().resolve("doc")));
    }

    @Test
    void extractsNestedTarGzFromJar() throws IOException {
        byte[] inner = new TestArchives.Tar()
                .file("build_meta.json", "{\"platform\":\"linux-amd64\"}")
                .file("libjcef.so", "so".getBytes(StandardCharsets.UTF_8), 0644)
                .gzipped();
        Path archive = write("natives.jar", TestArchives.zipWithBinary("jcef-natives-linux.tar.gz", inner));
        new ArchiveExtractor().extract(archive,
                artifact(RuntimeArtifact.Format.ZIP_NESTED_TAR_GZ, 0, List.of(), List.of("*.so")), out(), () -> false);
        assertTrue(Files.exists(out().resolve("build_meta.json")));
        assertTrue(Files.exists(out().resolve("libjcef.so")));
    }

    @Test
    void longNamesViaGnuAndPax() throws IOException {
        String longPath = "top/" + "a".repeat(120) + "/" + "b".repeat(80) + ".bin";
        String paxPath = "top/" + "c".repeat(150) + ".dat";
        byte[] tar = new TestArchives.Tar().gnuLongName(longPath, "gnu").paxPath(paxPath, "pax").gzipped();
        Path archive = write("long.tar.gz", tar);
        new ArchiveExtractor().extract(archive, artifact(RuntimeArtifact.Format.TAR_GZ, 1, List.of(), List.of()),
                out(), () -> false);
        assertEquals("gnu", Files.readString(out().resolve(longPath.substring(4))));
        assertEquals("pax", Files.readString(out().resolve(paxPath.substring(4))));
    }

    @Test
    void rejectsZipSlip() throws IOException {
        Path archive = write("slip.zip", TestArchives.zip("../../evil.txt", "owned"));
        assertThrows(ArchiveExtractor.UnsafeEntryException.class, () -> new ArchiveExtractor().extract(archive,
                artifact(RuntimeArtifact.Format.ZIP, 0, List.of(), List.of()), out(), () -> false));
        assertFalse(Files.exists(dir.getParent().resolve("evil.txt")));
    }

    @Test
    void rejectsTarTraversalHiddenMidPath() throws IOException {
        byte[] tar = new TestArchives.Tar().file("pkg/bin/../../../etc/passwd", "x").gzipped();
        Path archive = write("slip.tar.gz", tar);
        assertThrows(ArchiveExtractor.UnsafeEntryException.class, () -> new ArchiveExtractor().extract(archive,
                artifact(RuntimeArtifact.Format.TAR_GZ, 0, List.of(), List.of()), out(), () -> false));
    }

    @Test
    void rejectsAbsolutePathsAndDriveLetters() throws IOException {
        Path unix = write("abs.zip", TestArchives.zip("/etc/evil", "x"));
        assertThrows(ArchiveExtractor.UnsafeEntryException.class, () -> new ArchiveExtractor().extract(unix,
                artifact(RuntimeArtifact.Format.ZIP, 0, List.of(), List.of()), out(), () -> false));
        Path windows = write("drive.zip", TestArchives.zip("C:\\Windows\\evil.dll", "x"));
        assertThrows(ArchiveExtractor.UnsafeEntryException.class, () -> new ArchiveExtractor().extract(windows,
                artifact(RuntimeArtifact.Format.ZIP, 0, List.of(), List.of()), out(), () -> false));
    }

    @Test
    void rejectsSymlinkEscapingTheDestination() throws IOException {
        byte[] tar = new TestArchives.Tar().symlink("pkg/link", "../../../../etc").gzipped();
        Path archive = write("link.tar.gz", tar);
        assertThrows(ArchiveExtractor.UnsafeEntryException.class, () -> new ArchiveExtractor().extract(archive,
                artifact(RuntimeArtifact.Format.TAR_GZ, 0, List.of(), List.of()), out(), () -> false));
        byte[] absolute = new TestArchives.Tar().symlink("pkg/link", "/etc/passwd").gzipped();
        Path archive2 = write("link2.tar.gz", absolute);
        assertThrows(ArchiveExtractor.UnsafeEntryException.class, () -> new ArchiveExtractor().extract(archive2,
                artifact(RuntimeArtifact.Format.TAR_GZ, 0, List.of(), List.of()), out(), () -> false));
    }

    @Test
    void refusesToWriteThroughAnEarlierSymlink() throws IOException {
        // A safe-looking link to a sibling directory, followed by a file written
        // "through" it: classic two-step escape attempt.
        byte[] tar = new TestArchives.Tar()
                .directory("pkg/real/")
                .symlink("pkg/alias", "real")
                .file("pkg/alias/payload", "x")
                .gzipped();
        Path archive = write("through.tar.gz", tar);
        try {
            new ArchiveExtractor().extract(archive, artifact(RuntimeArtifact.Format.TAR_GZ, 0, List.of(), List.of()),
                    out(), () -> false);
        } catch (ArchiveExtractor.UnsafeEntryException expected) {
            return;
        } catch (IOException symlinksUnsupported) {
            return;   // e.g. Windows without developer mode: still nothing escaped
        }
        throw new AssertionError("writing through a symlinked directory must be refused");
    }

    @Test
    void rejectsHardLinks() throws IOException {
        byte[] tar = new TestArchives.Tar().file("pkg/a", "x").hardlink("pkg/b", "/etc/shadow").gzipped();
        Path archive = write("hard.tar.gz", tar);
        assertThrows(ArchiveExtractor.UnsafeEntryException.class, () -> new ArchiveExtractor().extract(archive,
                artifact(RuntimeArtifact.Format.TAR_GZ, 0, List.of(), List.of()), out(), () -> false));
    }

    @Test
    void rejectsCorruptHeaders() throws IOException {
        byte[] tar = new TestArchives.Tar().corruptHeader("pkg/file").gzipped();
        Path archive = write("corrupt.tar.gz", tar);
        IOException error = assertThrows(IOException.class, () -> new ArchiveExtractor().extract(archive,
                artifact(RuntimeArtifact.Format.TAR_GZ, 0, List.of(), List.of()), out(), () -> false));
        assertTrue(error.getMessage().contains("checksum"), error.getMessage());
    }

    @Test
    void rejectsTruncatedArchive() throws IOException {
        byte[] full = new TestArchives.Tar().file("pkg/file", new byte[4000], 0644).gzipped();
        Path archive = write("trunc.tar.gz", java.util.Arrays.copyOf(full, full.length / 2));
        assertThrows(IOException.class, () -> new ArchiveExtractor().extract(archive,
                artifact(RuntimeArtifact.Format.TAR_GZ, 0, List.of(), List.of()), out(), () -> false));
    }

    @Test
    void enforcesExpansionLimit() throws IOException {
        byte[] tar = new TestArchives.Tar().file("pkg/big", new byte[200_000], 0644).gzipped();
        Path archive = write("bomb.tar.gz", tar);
        IOException error = assertThrows(IOException.class, () -> new ArchiveExtractor(100_000, 100).extract(archive,
                artifact(RuntimeArtifact.Format.TAR_GZ, 0, List.of(), List.of()), out(), () -> false));
        assertTrue(error.getMessage().contains("limit"), error.getMessage());
    }

    @Test
    void enforcesEntryLimit() throws IOException {
        TestArchives.Tar tar = new TestArchives.Tar();
        for (int i = 0; i < 20; i++) {
            tar.file("pkg/f" + i, "x");
        }
        Path archive = write("many.tar.gz", tar.gzipped());
        assertThrows(IOException.class, () -> new ArchiveExtractor(1_000_000, 10).extract(archive,
                artifact(RuntimeArtifact.Format.TAR_GZ, 0, List.of(), List.of()), out(), () -> false));
    }

    @Test
    void sanitiseNormalisesHarmlessForms() throws IOException {
        assertEquals("bin/ffmpeg", ArchiveExtractor.sanitise("./pkg//bin/./ffmpeg", 1));
        assertEquals(null, ArchiveExtractor.sanitise("pkg/", 1));
        assertEquals("a/b", ArchiveExtractor.sanitise("a\\b", 0));
    }
}
