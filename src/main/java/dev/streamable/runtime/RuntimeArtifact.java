package dev.streamable.runtime;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One pinned, downloadable file for one platform.
 *
 * <p>Everything a download needs to be trustworthy is fixed here at build time:
 * the exact URLs (mirrors of the <em>same</em> bytes), the expected size and the
 * SHA-256 digest. Nothing is ever resolved from a "latest" pointer, and nothing
 * is executed or loaded before its digest has matched.</p>
 *
 * @param platform         platform the artifact is built for ({@link RuntimePlatform#ANY} for data files)
 * @param urls             HTTPS mirrors, tried in order; all must serve identical bytes
 * @param sha256           expected lowercase hex SHA-256 of the downloaded file
 * @param size             expected size in bytes, or {@code -1} when not pinned
 * @param format           how the file is unpacked
 * @param fileName         name the downloaded file is stored under
 * @param stripComponents  leading path segments removed from archive entries
 * @param include          glob patterns (after stripping) of entries to keep; empty keeps all
 * @param executables      paths (after stripping) that must be marked executable on POSIX systems
 */
public record RuntimeArtifact(
        RuntimePlatform platform,
        List<URI> urls,
        String sha256,
        long size,
        Format format,
        String fileName,
        int stripComponents,
        List<String> include,
        List<String> executables) {

    /** How a downloaded artifact is turned into an installed directory. */
    public enum Format {
        /** Stored as-is (model weights, a jar loaded later). */
        FILE,
        ZIP,
        TAR_GZ,
        TAR_XZ,
        /** A jar/zip whose single {@code .tar.gz} entry holds the real payload (JCEF natives). */
        ZIP_NESTED_TAR_GZ;

        public static Format parse(String value) {
            return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
                case "file" -> FILE;
                case "zip" -> ZIP;
                case "tar.gz", "tgz" -> TAR_GZ;
                case "tar.xz", "txz" -> TAR_XZ;
                case "zip+tar.gz", "jar+tar.gz" -> ZIP_NESTED_TAR_GZ;
                default -> throw new IllegalArgumentException("Unknown artifact format: " + value);
            };
        }
    }

    public RuntimeArtifact {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(format, "format");
        urls = List.copyOf(urls == null ? List.of() : urls);
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("An artifact needs at least one URL");
        }
        for (URI url : urls) {
            if (!"https".equalsIgnoreCase(url.getScheme())) {
                throw new IllegalArgumentException("Runtime artifacts must be fetched over HTTPS: " + url);
            }
        }
        sha256 = Sha256.normalise(sha256);
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..")) {
            throw new IllegalArgumentException("Invalid artifact file name: " + fileName);
        }
        stripComponents = Math.max(0, stripComponents);
        include = List.copyOf(include == null ? List.of() : include);
        executables = List.copyOf(executables == null ? List.of() : executables);
    }

    /** Whether an archive entry path (already stripped) should be extracted. */
    public boolean shouldInclude(String strippedPath) {
        if (include.isEmpty()) {
            return true;
        }
        for (String pattern : include) {
            if (Glob.matches(pattern, strippedPath)) {
                return true;
            }
        }
        return false;
    }

    /** Whether an extracted path must be executable. */
    public boolean isExecutable(String strippedPath) {
        for (String pattern : executables) {
            if (Glob.matches(pattern, strippedPath)) {
                return true;
            }
        }
        return false;
    }

    /** Minimal glob: {@code *} matches within a segment, {@code **} across segments. */
    static final class Glob {
        private Glob() {
        }

        static boolean matches(String pattern, String path) {
            return match(pattern, 0, path, 0);
        }

        private static boolean match(String p, int pi, String s, int si) {
            while (pi < p.length()) {
                char c = p.charAt(pi);
                if (c == '*') {
                    boolean doubleStar = pi + 1 < p.length() && p.charAt(pi + 1) == '*';
                    int next = doubleStar ? pi + 2 : pi + 1;
                    if (doubleStar && next < p.length() && p.charAt(next) == '/') {
                        // "**/" also matches zero directories.
                        if (match(p, next + 1, s, si)) {
                            return true;
                        }
                    }
                    for (int k = si; k <= s.length(); k++) {
                        if (match(p, next, s, k)) {
                            return true;
                        }
                        if (k < s.length() && s.charAt(k) == '/' && !doubleStar) {
                            return false;
                        }
                    }
                    return false;
                }
                if (si >= s.length() || s.charAt(si) != c) {
                    return false;
                }
                pi++;
                si++;
            }
            return si == s.length();
        }
    }
}
