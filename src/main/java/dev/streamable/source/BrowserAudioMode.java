package dev.streamable.source;

/**
 * Where a browser source's audio should be heard.
 *
 * <p>Mirrors OBS's audio monitoring options. "Stream" means the program mix,
 * so it reaches recordings and clips as well as the broadcast. Implemented by
 * the in-page audio tap; see {@code dev.streamable.browser.audio.BrowserAudioBridge}
 * for what it can and cannot capture.</p>
 */
public enum BrowserAudioMode {
    /** Muted everywhere. */
    OFF("Off"),
    /** The player hears it locally; viewers do not. */
    MONITOR_ONLY("Monitor Only"),
    /** Mixed into the broadcast only; the player does not hear it. */
    STREAM_ONLY("Recording & stream only"),
    /** Both. */
    MONITOR_AND_STREAM("Monitor + recording & stream");

    private final String displayName;

    BrowserAudioMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
