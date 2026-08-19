package dev.streamable.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central redaction utility for stream credentials.
 *
 * <p>Stream keys are credentials: anyone holding one can broadcast to the
 * channel. They must therefore never reach a log file, a toast, a crash report,
 * an exception message or a diagnostic export. Every path that can surface text
 * originating from FFmpeg, from a URL, or from an exception runs through this
 * class first.</p>
 *
 * <p>The class is deliberately paranoid and stateless: it redacts both keys it
 * has been told about ({@link #redact(String, String...)}) and anything that
 * merely <em>looks</em> like a stream key in an ingest URL, because FFmpeg
 * echoes the full publish URL back in its own error output.</p>
 */
public final class SecretRedactor {

    /** Replacement token written in place of any secret. */
    public static final String MASK = "<REDACTED>";

    /**
     * Matches the last path segment of an RTMP-family publish URL, which is
     * where every major platform puts the stream key
     * (e.g. {@code rtmp://host/app/live_1234_abcdef}).
     */
    private static final Pattern RTMP_URL_TAIL = Pattern.compile(
            "\\b(rtmps?|rtsp|srt|http|https)://([^\\s\"']*/)([^/\\s\"']+)",
            Pattern.CASE_INSENSITIVE);

    /** Matches {@code ?key=...}, {@code &streamkey=...}, {@code ...} query credentials. */
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "([?&](?:streamkey|stream_key|key|token|password|passphrase|auth)=)([^&\\s\"']+)",
            Pattern.CASE_INSENSITIVE);

    /** Well-known stream-key shapes (Twitch {@code live_...}, YouTube {@code xxxx-xxxx-...}). */
    private static final Pattern KEY_SHAPE = Pattern.compile(
            "\\blive_\\d+_[A-Za-z0-9]+\\b|\\b[a-z0-9]{4}(?:-[a-z0-9]{4}){3,}\\b",
            Pattern.CASE_INSENSITIVE);

    private SecretRedactor() {
    }

    /**
     * Redacts a text blob before it is logged or shown to the user.
     *
     * @param text            arbitrary text (FFmpeg stderr, exception message, ...)
     * @param knownSecrets    additional literal secrets to strip; nulls/blanks ignored
     * @return the text with every recognised credential replaced by {@link #MASK}
     */
    public static String redact(String text, String... knownSecrets) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;

        // 1. Literal secrets we were explicitly given always go first: they are
        //    the only ones we can remove with certainty.
        if (knownSecrets != null) {
            for (String secret : knownSecrets) {
                if (secret != null && secret.length() >= 4) {
                    result = result.replace(secret, MASK);
                }
            }
        }

        // 2. Query-string credentials.
        result = QUERY_SECRET.matcher(result).replaceAll(mr -> Matcher.quoteReplacement(mr.group(1)) + MASK);

        // 3. The final path segment of any ingest URL.
        result = RTMP_URL_TAIL.matcher(result).replaceAll(
                mr -> Matcher.quoteReplacement(mr.group(1) + "://" + mr.group(2)) + MASK);

        // 4. Anything shaped like a known platform key, wherever it appears.
        result = KEY_SHAPE.matcher(result).replaceAll(MASK);

        return result;
    }

    /**
     * Builds a display-safe version of a publish URL, keeping enough structure
     * for the user to recognise the destination but never the key itself.
     */
    public static String redactUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return redact(url.trim());
    }

    /**
     * Masks a secret for inline display, e.g. in a settings row.
     *
     * @return {@code ""} for blank input, otherwise a fixed-width dot mask that
     * does not leak the real length beyond "short vs long".
     */
    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        return "\u2022".repeat(Math.min(24, Math.max(8, secret.length())));
    }

    /**
     * Returns {@code true} when the string plausibly contains a credential and
     * therefore must not be written anywhere durable without redaction.
     */
    public static boolean looksSensitive(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("streamkey") || lower.contains("stream_key")
                || KEY_SHAPE.matcher(text).find()
                || QUERY_SECRET.matcher(text).find();
    }
}
