package dev.streamable.streaming;

import dev.streamable.util.SecretRedactor;

import java.util.Objects;

/**
 * An ingest URL plus its stream key.
 *
 * <p>This type exists so that the joining rule and the redaction rule live in
 * exactly one place. Its {@link #toString()} is deliberately safe: the key is
 * never included, which means an accidental {@code log.info("{}", creds)} or a
 * credential captured in a crash report cannot leak it.</p>
 */
public record StreamingCredentials(String ingestUrl, String streamKey) {

    public StreamingCredentials {
        ingestUrl = ingestUrl == null ? "" : ingestUrl.trim();
        streamKey = streamKey == null ? "" : streamKey.trim();
    }

    /**
     * Builds the full publish target handed to FFmpeg.
     *
     * <p>Joins with exactly one {@code /} regardless of whether the user pasted
     * a trailing slash on the URL or a leading slash on the key. If the key is
     * blank the URL is used unchanged, which supports endpoints that embed the
     * key in the URL already (common for custom/self-hosted ingests).</p>
     */
    public String publishUrl() {
        if (streamKey.isEmpty()) {
            return ingestUrl;
        }
        if (ingestUrl.isEmpty()) {
            return streamKey;
        }
        String base = ingestUrl.endsWith("/") ? ingestUrl.substring(0, ingestUrl.length() - 1) : ingestUrl;
        String key = streamKey.startsWith("/") ? streamKey.substring(1) : streamKey;
        return base + "/" + key;
    }

    /** The publish URL with the key masked - safe for logs, toasts and UI. */
    public String redactedPublishUrl() {
        String url = publishUrl();
        if (url.isEmpty()) {
            return "";
        }
        if (!streamKey.isEmpty()) {
            return url.replace(streamKey, SecretRedactor.MASK);
        }
        return SecretRedactor.redactUrl(url);
    }

    /** Validates the pair, returning {@code null} when usable or a user-facing reason. */
    public String validate(boolean keyRequired) {
        if (ingestUrl.isEmpty()) {
            return "Stream URL is empty.";
        }
        String lower = ingestUrl.toLowerCase(java.util.Locale.ROOT);
        boolean known = lower.startsWith("rtmp://") || lower.startsWith("rtmps://")
                || lower.startsWith("srt://") || lower.startsWith("rtsp://")
                || lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("udp://") || lower.startsWith("tcp://");
        if (!known) {
            return "Stream URL must start with rtmp://, rtmps:// or another supported protocol.";
        }
        if (ingestUrl.chars().anyMatch(Character::isWhitespace)) {
            return "Stream URL must not contain spaces.";
        }
        if (keyRequired && streamKey.isEmpty()) {
            return "Stream key is empty.";
        }
        if (streamKey.chars().anyMatch(Character::isWhitespace)) {
            return "Stream key must not contain spaces.";
        }
        return null;
    }

    public boolean isUsable(boolean keyRequired) {
        return validate(keyRequired) == null;
    }

    /** Never includes the stream key. */
    @Override
    public String toString() {
        return "StreamingCredentials[url=" + SecretRedactor.redactUrl(ingestUrl)
                + ", key=" + (streamKey.isEmpty() ? "<none>" : SecretRedactor.MASK) + "]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StreamingCredentials(String url, String key)
                && ingestUrl.equals(url) && streamKey.equals(key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ingestUrl, streamKey);
    }
}
