package dev.streamable.streaming;

/**
 * Presets for well-known ingest services.
 *
 * <p>Kick is deliberately absent: its ingest host and path are per-account, so
 * no preset can be correct, and "Custom RTMP/RTMPS" handles it exactly as well.
 * A preset that has to be overwritten before it works is worse than no preset.
 *
 * <p>These are convenience templates only. Stream-able never depends on a
 * platform REST API to go live: everything is protocol based (RTMP/RTMPS), so
 * any service that hands out a server URL and a stream key works, including
 * ones that do not exist yet. Ingest hostnames change over time, so the
 * defaults here are always user-overridable and are treated purely as a
 * starting value in the UI.</p>
 */
public enum StreamPlatform {

    TWITCH("Twitch", "rtmp://live.twitch.tv/app", true,
            "Twitch rejects bitrates above ~8000 kbps for non-partners and requires a 2s keyframe interval."),
    YOUTUBE("YouTube", "rtmp://a.rtmp.youtube.com/live2", true,
            "YouTube requires a 2s (or shorter) keyframe interval; enable the stream in YouTube Studio first."),
    X("X / Twitter", "rtmps://va.pscp.tv:443/x", true,
            "X ingest endpoints are region specific; copy the exact URL from the X producer view."),
    CUSTOM("Custom RTMP/RTMPS", "", false,
            "Any RTMP, RTMPS, or other FFmpeg-supported output URL.");

    private final String displayName;
    private final String defaultIngestUrl;
    private final boolean keyRequired;
    private final String advice;

    StreamPlatform(String displayName, String defaultIngestUrl, boolean keyRequired, String advice) {
        this.displayName = displayName;
        this.defaultIngestUrl = defaultIngestUrl;
        this.keyRequired = keyRequired;
        this.advice = advice;
    }

    public String displayName() {
        return displayName;
    }

    /** Suggested ingest URL; may be empty for {@link #CUSTOM}. Always overridable. */
    public String defaultIngestUrl() {
        return defaultIngestUrl;
    }

    /** Whether a separate stream key is normally required in addition to the URL. */
    public boolean keyRequired() {
        return keyRequired;
    }

    /** Short human-readable guidance shown next to the destination in the UI. */
    public String advice() {
        return advice;
    }
}
