package dev.streamable.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** SHA-256 helpers. The only integrity check the runtime subsystem trusts. */
public final class Sha256 {

    private static final HexFormat HEX = HexFormat.of();

    private Sha256() {
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java SE implementation must provide SHA-256.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Validates and lower-cases a hex digest. */
    public static String normalise(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("Missing SHA-256 digest");
        }
        String value = hex.trim().toLowerCase(Locale.ROOT);
        if (value.length() != 64 || !value.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new IllegalArgumentException("Not a SHA-256 hex digest: " + hex);
        }
        return value;
    }

    public static String hash(Path file) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HEX.formatHex(digest.digest());
    }

    public static String hash(byte[] data) {
        return HEX.formatHex(newDigest().digest(data));
    }

    public static String toHex(byte[] digest) {
        return HEX.formatHex(digest);
    }

    /** Constant-time comparison of two hex digests. */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                actual.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
