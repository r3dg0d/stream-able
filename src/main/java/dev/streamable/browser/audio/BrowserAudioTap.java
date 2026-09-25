package dev.streamable.browser.audio;

import dev.streamable.StreamAbleLog;
import dev.streamable.source.BrowserAudioMode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * The in-page browser audio tap: the script injected into every browser
 * source, and the parser for the PCM chunks it sends back.
 *
 * <p>The page sends {@code sa-pcm:<stream>:<rate>:<channels>:<base64 s16le>}
 * through a JCEF message router query. Input from a page is untrusted, so
 * every field is range-checked and oversized chunks are refused.</p>
 */
public final class BrowserAudioTap {

    /** The JavaScript function the message router defines in every page. */
    public static final String QUERY_FUNCTION = "streamableAudioQuery";
    public static final String CANCEL_FUNCTION = "streamableAudioQueryCancel";
    static final String PREFIX = "sa-pcm:";
    /** Largest accepted chunk: 16 384 stereo frames (~0.34 s at 48 kHz). */
    static final int MAX_SAMPLES = 16_384 * 2;
    /** Base64 of {@link #MAX_SAMPLES} 16-bit samples plus the header, with room to spare. */
    static final int MAX_REQUEST_CHARS = MAX_SAMPLES * 2 * 4 / 3 + 256;

    /** One decoded chunk: interleaved 16-bit samples. */
    public record Chunk(int stream, int sampleRate, int channels, short[] samples) {
    }

    private static final String SCRIPT = load();

    private BrowserAudioTap() {
    }

    private static String load() {
        try (InputStream in = BrowserAudioTap.class.getResourceAsStream("/assets/streamable/browser/audio-tap.js")) {
            if (in == null) {
                StreamAbleLog.BROWSER.error("Browser audio tap script is missing from the mod jar.");
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            StreamAbleLog.BROWSER.error("Could not read the browser audio tap script", e);
            return "";
        }
    }

    /** Whether the page should play the source locally. */
    static boolean monitors(BrowserAudioMode mode) {
        return mode == BrowserAudioMode.MONITOR_ONLY || mode == BrowserAudioMode.MONITOR_AND_STREAM;
    }

    /** Whether the page's audio should reach recordings and the stream. */
    public static boolean captures(BrowserAudioMode mode) {
        return mode == BrowserAudioMode.STREAM_ONLY || mode == BrowserAudioMode.MONITOR_AND_STREAM;
    }

    /** The script to inject (or re-inject: it then only updates its settings). */
    public static String script(BrowserAudioMode mode, float volume) {
        if (SCRIPT.isEmpty()) {
            return "";
        }
        BrowserAudioMode m = mode == null ? BrowserAudioMode.MONITOR_ONLY : mode;
        float v = Float.isFinite(volume) ? Math.clamp(volume, 0f, 2f) : 1f;
        String config = String.format(Locale.ROOT, "{\"monitor\":%s,\"capture\":%s,\"volume\":%.3f}",
                monitors(m), captures(m), v);
        return SCRIPT.replace("__SA_CONFIG__", config);
    }

    /**
     * Parses a query from a page.
     *
     * @return the chunk, or {@code null} when the request is not a valid audio chunk
     */
    public static Chunk parse(String request) {
        if (request == null || !request.startsWith(PREFIX) || request.length() > MAX_REQUEST_CHARS) {
            return null;
        }
        String[] parts = request.substring(PREFIX.length()).split(":", 4);
        if (parts.length != 4) {
            return null;
        }
        try {
            int stream = Integer.parseInt(parts[0]);
            int rate = Integer.parseInt(parts[1]);
            int channels = Integer.parseInt(parts[2]);
            if (stream < 1 || stream > 1_000_000 || rate < 8_000 || rate > 192_000 || channels < 1 || channels > 2) {
                return null;
            }
            byte[] bytes = Base64.getDecoder().decode(parts[3]);
            if (bytes.length == 0 || bytes.length % (2 * channels) != 0 || bytes.length / 2 > MAX_SAMPLES) {
                return null;
            }
            short[] samples = new short[bytes.length / 2];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8));
            }
            return new Chunk(stream, rate, channels, samples);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
