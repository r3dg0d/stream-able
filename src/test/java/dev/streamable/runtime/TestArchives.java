package dev.streamable.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds small, possibly hostile, archives in memory for extractor tests. */
final class TestArchives {

    private TestArchives() {
    }

    /** Minimal ustar writer supporting every entry type the extractor must judge. */
    static final class Tar {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Tar file(String name, String content) throws IOException {
            return file(name, content.getBytes(StandardCharsets.UTF_8), 0644);
        }

        Tar file(String name, byte[] content, int mode) throws IOException {
            header(name, content.length, '0', "", mode, true);
            out.write(content);
            pad(content.length);
            return this;
        }

        Tar directory(String name) throws IOException {
            header(name, 0, '5', "", 0755, true);
            return this;
        }

        Tar symlink(String name, String target) throws IOException {
            header(name, 0, '2', target, 0777, true);
            return this;
        }

        Tar hardlink(String name, String target) throws IOException {
            header(name, 0, '1', target, 0644, true);
            return this;
        }

        Tar gnuLongName(String longName, String content) throws IOException {
            byte[] nameBytes = (longName + "\0").getBytes(StandardCharsets.UTF_8);
            header("././@LongLink", nameBytes.length, 'L', "", 0644, true);
            out.write(nameBytes);
            pad(nameBytes.length);
            return file("placeholder-short-name", content);
        }

        Tar paxPath(String path, String content) throws IOException {
            String record = " path=" + path + "\n";
            int length = record.length();
            // The length prefix counts itself.
            int total = length + String.valueOf(length).length();
            if (String.valueOf(total).length() != String.valueOf(length).length()) {
                total++;
            }
            byte[] recordBytes = (total + record).getBytes(StandardCharsets.UTF_8);
            header("PaxHeader", recordBytes.length, 'x', "", 0644, true);
            out.write(recordBytes);
            pad(recordBytes.length);
            return file("pax-short", content);
        }

        Tar corruptHeader(String name) throws IOException {
            header(name, 0, '0', "", 0644, false);
            return this;
        }

        byte[] bytes() throws IOException {
            out.write(new byte[1024]);   // two zero blocks
            return out.toByteArray();
        }

        byte[] gzipped() throws IOException {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write(bytes());
            }
            return compressed.toByteArray();
        }

        private void header(String name, long size, char type, String linkName, int mode, boolean validChecksum)
                throws IOException {
            byte[] header = new byte[512];
            put(header, 0, 100, name);
            put(header, 100, 8, octal(mode, 7));
            put(header, 108, 8, octal(0, 7));
            put(header, 116, 8, octal(0, 7));
            put(header, 124, 12, octal(size, 11));
            put(header, 136, 12, octal(0, 11));
            for (int i = 148; i < 156; i++) {
                header[i] = ' ';
            }
            header[156] = (byte) type;
            put(header, 157, 100, linkName);
            put(header, 257, 6, "ustar");
            header[263] = '0';
            header[264] = '0';
            long sum = 0;
            for (byte b : header) {
                sum += b & 0xFF;
            }
            if (!validChecksum) {
                sum += 7;
            }
            put(header, 148, 8, octal(sum, 6) + "\0 ");
            out.write(header);
        }

        private void pad(long written) {
            long remainder = written % 512;
            if (remainder != 0) {
                out.writeBytes(new byte[(int) (512 - remainder)]);
            }
        }

        private static String octal(long value, int digits) {
            String text = Long.toOctalString(value);
            return "0".repeat(Math.max(0, digits - text.length())) + text;
        }

        private static void put(byte[] header, int offset, int length, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(bytes, 0, header, offset, Math.min(length, bytes.length));
        }
    }

    static byte[] zip(String... namesAndContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                if (!namesAndContents[i].endsWith("/")) {
                    zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static byte[] zipWithBinary(String name, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    static void copy(byte[] data, OutputStream out) throws IOException {
        out.write(data);
    }
}
