package dev.streamable.source;

/**
 * Which outputs a source appears in.
 *
 * <p>These are independent on purpose. A streamer often wants an alert overlay
 * visible to viewers without it covering their own screen, or wants a private
 * dashboard on screen that never reaches the broadcast. Because the compositor
 * builds the program frame in an off-screen framebuffer rather than reading it
 * back from the visible window, all four combinations are actually achievable.</p>
 *
 * @param showLocally         draw on the player's own screen
 * @param includeInRecording  include in the local recording
 * @param includeInStream     include in the broadcast
 */
public record OutputRouting(boolean showLocally, boolean includeInRecording, boolean includeInStream) {

    /** Everywhere - the default for a newly added source. */
    public static final OutputRouting ALL = new OutputRouting(true, true, true);
    /** Viewers only: hidden from the player's own screen. */
    public static final OutputRouting VIEWERS_ONLY = new OutputRouting(false, true, true);
    /** Player only: never reaches an output. */
    public static final OutputRouting LOCAL_ONLY = new OutputRouting(true, false, false);

    public boolean anyOutput() {
        return includeInRecording || includeInStream;
    }

    public boolean anywhere() {
        return showLocally || anyOutput();
    }

    public OutputRouting withShowLocally(boolean value) {
        return new OutputRouting(value, includeInRecording, includeInStream);
    }

    public OutputRouting withIncludeInRecording(boolean value) {
        return new OutputRouting(showLocally, value, includeInStream);
    }

    public OutputRouting withIncludeInStream(boolean value) {
        return new OutputRouting(showLocally, includeInRecording, value);
    }
}
