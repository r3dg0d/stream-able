package dev.streamable.input;

/**
 * What the Source Editor screen does with input.
 *
 * <p>Made an explicit state rather than a scattering of booleans, because the
 * three cases have genuinely different routing rules and getting them confused
 * is what causes a click to both drag an overlay and swing a pickaxe.</p>
 *
 * <p>When no editor screen is open the mode is irrelevant: browser sources
 * receive no input at all, so gameplay keys and clicks behave exactly as they
 * would without Stream-able installed.</p>
 */
public enum EditorMode {
    /** Clicks select, move, resize and rotate sources. */
    TRANSFORM("Transform"),
    /** Mouse and keyboard are forwarded into Chromium. */
    INTERACT("Interact");

    private final String displayName;

    EditorMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public EditorMode toggled() {
        return this == TRANSFORM ? INTERACT : TRANSFORM;
    }
}
