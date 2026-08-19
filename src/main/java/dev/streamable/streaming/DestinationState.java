package dev.streamable.streaming;

/** Connection state of a single streaming destination. */
public enum DestinationState {
    /** Not streaming. */
    OFFLINE("Offline", 0xFF9E9E9E),
    /** Handshake in progress. */
    CONNECTING("Connecting", 0xFFFFC107),
    /** Publishing normally. */
    LIVE("Live", 0xFF4CAF50),
    /** Dropped and waiting to retry. */
    RECONNECTING("Reconnecting", 0xFFFF9800),
    /** Failed and no longer retrying. */
    ERROR("Error", 0xFFF44336),
    /** Turned off by the user. */
    DISABLED("Disabled", 0xFF616161);

    private final String displayName;
    private final int colour;

    DestinationState(String displayName, int colour) {
        this.displayName = displayName;
        this.colour = colour;
    }

    public String displayName() {
        return displayName;
    }

    /** ARGB colour for the status dot in the UI and health HUD. */
    public int colour() {
        return colour;
    }

    public boolean isActive() {
        return this == CONNECTING || this == LIVE || this == RECONNECTING;
    }
}
