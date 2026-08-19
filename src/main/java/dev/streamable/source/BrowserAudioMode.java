package dev.streamable.source;

/**
 * Where a browser source's audio should be heard.
 *
 * <p>Mirrors OBS's audio monitoring options. See
 * {@code dev.streamable.browser.audio.BrowserAudioBridge} for the current
 * capability status - on the JCEF build Stream-able targets, Chromium audio
 * cannot be intercepted, so these settings are persisted and surfaced but only
 * {@link #MONITOR_ONLY} is actually achievable without an explicit system
 * loopback capture device.</p>
 */
public enum BrowserAudioMode {
    /** Muted everywhere. */
    OFF("Off"),
    /** The player hears it locally; viewers do not. */
    MONITOR_ONLY("Monitor Only"),
    /** Mixed into the broadcast only; the player does not hear it. */
    STREAM_ONLY("Stream Only"),
    /** Both. */
    MONITOR_AND_STREAM("Monitor + Stream");

    private final String displayName;

    BrowserAudioMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
