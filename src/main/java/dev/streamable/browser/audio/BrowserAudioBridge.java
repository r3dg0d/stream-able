package dev.streamable.browser.audio;

import dev.streamable.StreamAbleLog;
import dev.streamable.source.BrowserAudioMode;
import dev.streamable.source.BrowserSource;

/**
 * Browser-source audio capture, and an honest account of its limits.
 *
 * <h2>Why not the native route</h2>
 * <p>CEF has {@code CefAudioHandler} ({@code OnAudioStreamPacket}), the clean
 * per-browser PCM callback, but the JCEF build that MCEF Modern
 * {@code 0.3.3+mc26.1.jcef146.0.10} bundles ({@code me.friwi:jcef-api} at
 * {@code cef-146.0.10}) contains no audio handler of any kind, so there is no
 * native API to attach to.</p>
 *
 * <h2>What Stream-able does instead</h2>
 * <p>An in-page tap ({@link BrowserAudioTap}, {@code audio-tap.js}) routes what
 * each page plays - {@code <audio>}/{@code <video>} elements and the page's
 * own Web Audio - through a Web Audio graph inside the page, and sends 16-bit
 * PCM to Java through a JCEF message router. From there it is resampled and
 * mixed into the browser bus, one stream per page audio context. A gain node
 * in the page implements each source's mode, so "Stream only" really is
 * silent on the player's speakers.</p>
 *
 * <p>MCEF Modern starts Chromium with {@code --autoplay-policy=no-user-gesture-required}
 * (alerts play without a click) and {@code --disable-web-security}, so media
 * from other sites is not "tainted" and can be captured.</p>
 *
 * <h2>Limits</h2>
 * <ul>
 *   <li>Speech synthesis ({@code speechSynthesis}) and audio playing inside
 *       iframes are not routed through the tap: they are heard locally but do
 *       not reach outputs, and "Off" / "Stream only" cannot silence them.</li>
 *   <li>Latency is about one Web Audio block (~40 ms) plus the mixer's.</li>
 * </ul>
 */
public final class BrowserAudioBridge {

    /** What the current engine can do. */
    public enum Capability {
        /** Page audio captured by the in-page tap. */
        IN_PAGE_CAPTURE,
        /** The browser engine is not running; nothing to capture. */
        UNAVAILABLE
    }

    private static final String LIMITS =
            "Page audio from <audio>/<video> elements and Web Audio is captured inside the page. "
                    + "Speech synthesis and audio inside iframes are only heard locally.";

    private BrowserAudioBridge() {
    }

    public static Capability capability() {
        return Capability.IN_PAGE_CAPTURE;
    }

    public static boolean canCaptureBrowserAudio() {
        return capability() == Capability.IN_PAGE_CAPTURE;
    }

    /** What browser audio capture does and does not cover, for the UI and logs. */
    public static String limitationReason() {
        return LIMITS;
    }

    /**
     * A note for a source whose mode sends audio to outputs, or {@code null}
     * when its mode needs no caveat.
     */
    public static String describeLimitation(BrowserSource source) {
        return switch (source.audioMode()) {
            case OFF, MONITOR_ONLY -> null;
            case STREAM_ONLY, MONITOR_AND_STREAM -> "This page's audio is mixed into recordings and the stream "
                    + "(Browser Sources bus). " + LIMITS;
        };
    }

    /** Logs the capability once at startup so it is visible in bug reports. */
    public static void logCapability() {
        StreamAbleLog.BROWSER.info("Browser audio capability: {} - {}", capability(), LIMITS);
    }

    /** The modes the engine can honour: all of them, with the limits above. */
    public static boolean supports(BrowserAudioMode mode) {
        return mode != null;
    }
}
