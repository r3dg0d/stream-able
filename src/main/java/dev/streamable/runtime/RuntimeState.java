package dev.streamable.runtime;

/**
 * Lifecycle of one managed runtime component.
 *
 * <pre>
 *   NOT_INSTALLED -> CHECKING -> DOWNLOADING -> VERIFYING -> EXTRACTING
 *                 -> INSTALLING -> INITIALIZING -> READY
 *                                   \-> FAILED (from any step)
 * </pre>
 *
 * <p>{@link #UPDATE_AVAILABLE} means a working older install exists while the
 * pinned version for this build is not installed yet; the old one keeps
 * working until the upgrade completes. {@link #UNSUPPORTED} means no artifact is
 * pinned for this platform at all.</p>
 */
public enum RuntimeState {
    NOT_INSTALLED("Not installed", false),
    CHECKING("Checking", true),
    DOWNLOADING("Downloading", true),
    VERIFYING("Verifying", true),
    EXTRACTING("Extracting", true),
    INSTALLING("Installing", true),
    INITIALIZING("Initializing", true),
    READY("Ready", false),
    UPDATE_AVAILABLE("Update available", false),
    FAILED("Failed", false),
    UNSUPPORTED("Not available on this platform", false);

    private final String displayName;
    private final boolean busy;

    RuntimeState(String displayName, boolean busy) {
        this.displayName = displayName;
        this.busy = busy;
    }

    public String displayName() {
        return displayName;
    }

    /** True while work is in flight; the UI shows a progress bar. */
    public boolean isBusy() {
        return busy;
    }
}
