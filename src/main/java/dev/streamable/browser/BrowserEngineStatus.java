package dev.streamable.browser;

/**
 * Availability of the underlying browser engine.
 *
 * <p>Browser sources are a bonus, not a prerequisite: if Chromium cannot start,
 * recording and streaming must keep working. Every consumer therefore checks
 * this status rather than assuming a backend exists.</p>
 *
 * @param state   coarse state for UI branching
 * @param detail  human-readable explanation, safe to show in a dialog
 * @param percent initialisation progress in {@code [0,100]}, or {@code -1} if unknown
 */
public record BrowserEngineStatus(State state, String detail, float percent) {

    public enum State {
        /** MCEF is not installed; browser sources are hidden rather than broken. */
        NOT_INSTALLED,
        /** Downloading/extracting/starting Chromium. Sources show a placeholder. */
        INITIALISING,
        /** Ready to create browsers. */
        READY,
        /** Initialisation failed. Recording and streaming remain available. */
        FAILED
    }

    public static final BrowserEngineStatus NOT_INSTALLED = new BrowserEngineStatus(
            State.NOT_INSTALLED,
            "MCEF Modern is not installed. Browser sources require it; recording and streaming work without it.",
            -1);

    public static BrowserEngineStatus initialising(String stage, float percent) {
        return new BrowserEngineStatus(State.INITIALISING, stage, percent);
    }

    public static BrowserEngineStatus ready() {
        return new BrowserEngineStatus(State.READY, "Browser engine ready.", 100);
    }

    public static BrowserEngineStatus failed(String detail) {
        return new BrowserEngineStatus(State.FAILED, detail, -1);
    }

    public boolean isReady() {
        return state == State.READY;
    }

    public boolean isUsableLater() {
        return state == State.INITIALISING;
    }

    /** Short line for the Sources panel. */
    public String shortLabel() {
        return switch (state) {
            case NOT_INSTALLED -> "Browser engine not installed";
            case INITIALISING -> percent >= 0
                    ? "Starting browser engine... " + Math.round(percent) + "%"
                    : "Starting browser engine...";
            case READY -> "Browser engine ready";
            case FAILED -> "Browser engine unavailable";
        };
    }
}
