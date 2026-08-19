package dev.streamable.ffmpeg;

/** Rate-control strategy. Live ingests want CBR; local recordings usually want quality-based. */
public enum RateControl {
    /** Constant bitrate - what RTMP ingests expect, and what keeps buffers predictable. */
    CBR("CBR"),
    /** Variable bitrate with a ceiling. */
    VBR("VBR"),
    /** Constant quality (CRF / CQ). Not valid for live output. */
    CONSTANT_QUALITY("Constant Quality");

    private final String displayName;

    RateControl(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isLiveSafe() {
        return this != CONSTANT_QUALITY;
    }
}
