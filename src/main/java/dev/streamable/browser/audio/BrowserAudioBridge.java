package dev.streamable.browser.audio;

import dev.streamable.StreamAbleLog;
import dev.streamable.source.BrowserAudioMode;
import dev.streamable.source.BrowserSource;

/**
 * Browser-source audio capture - and an honest account of why it is limited.
 *
 * <h2>What was investigated</h2>
 * <p>CEF exposes {@code CefAudioHandler} ({@code OnAudioStreamPacket}), which is
 * exactly the clean callback needed: it delivers a browser's decoded PCM per
 * browser, without touching the operating system's mixer. Routing alert sounds
 * into the broadcast through it would be straightforward.</p>
 *
 * <p><b>That handler does not exist in the JCEF build Stream-able targets.</b>
 * The MCEF Modern release compatible with Minecraft 26.1
 * ({@code 0.3.3+mc26.1.jcef146.0.10}) bundles
 * {@code me.friwi:jcef-api} at {@code cef-146.0.10}, whose {@code org.cef.handler}
 * package contains no audio handler of any kind - only display, load, render,
 * keyboard, focus, lifespan, request and friends. There is therefore no Java
 * API to attach to, with or without a patched MCEF: the binding simply is not
 * present in the native bridge.</p>
 *
 * <h2>What Stream-able does instead</h2>
 * <ul>
 *   <li>Chromium keeps playing audio to the system output device, so the player
 *       <em>hears</em> alerts. That is {@link BrowserAudioMode#MONITOR_ONLY} and
 *       it works today with no extra configuration.</li>
 *   <li>Per-source audio settings are still stored and shown, so configurations
 *       survive until the capability lands.</li>
 *   <li>Getting browser audio into the broadcast requires capturing the system
 *       output. Stream-able does not do that silently - it would capture every
 *       sound on the machine, not just the overlay - so it is an explicit,
 *       clearly labelled opt-in described in the README.</li>
 * </ul>
 *
 * <p>No part of this class pretends to capture browser audio. When a source asks
 * for a mode that cannot be honoured, {@link #describeLimitation(BrowserSource)}
 * returns the reason for the UI to show.</p>
 */
public final class BrowserAudioBridge {

    /** What the current engine can actually do. */
    public enum Capability {
        /** Per-browser PCM capture available (not reachable on this JCEF build). */
        CAPTURE_SUPPORTED,
        /** Audio plays to the system device only. */
        MONITOR_ONLY
    }

    private static final String REASON =
            "JCEF 146.0.10 (bundled by MCEF Modern for Minecraft 26.1) exposes no CefAudioHandler, "
                    + "so Chromium audio cannot be captured per browser. Alerts are still audible "
                    + "locally; to include them in the broadcast, enable system audio capture in "
                    + "Audio settings.";

    private BrowserAudioBridge() {
    }

    /** Always {@link Capability#MONITOR_ONLY} on the supported JCEF build. */
    public static Capability capability() {
        return Capability.MONITOR_ONLY;
    }

    public static boolean canCaptureBrowserAudio() {
        return capability() == Capability.CAPTURE_SUPPORTED;
    }

    /** The reason browser audio cannot reach the stream, for UI and logs. */
    public static String limitationReason() {
        return REASON;
    }

    /**
     * A warning for a source whose audio mode cannot be honoured, or {@code null}
     * when its configuration is fully achievable.
     */
    public static String describeLimitation(BrowserSource source) {
        if (canCaptureBrowserAudio()) {
            return null;
        }
        return switch (source.audioMode()) {
            case OFF, MONITOR_ONLY -> null;
            case STREAM_ONLY, MONITOR_AND_STREAM ->
                    "This source's audio cannot be mixed into the broadcast. " + REASON;
        };
    }

    /** Logs the capability once at startup so it is visible in bug reports. */
    public static void logCapability() {
        StreamAbleLog.BROWSER.info("Browser audio capability: {} - {}", capability(), REASON);
    }
}
