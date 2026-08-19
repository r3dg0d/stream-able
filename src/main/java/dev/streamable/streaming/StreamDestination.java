package dev.streamable.streaming;

import dev.streamable.ffmpeg.EncodeProfile;
import dev.streamable.util.SecretRedactor;

import java.util.Objects;
import java.util.UUID;

/**
 * One streaming destination: where to publish, and how it is currently doing.
 *
 * <p>Configuration fields (name, platform, credentials, enabled, profile
 * override) are persisted. Runtime fields (state, error, attempt counter) are
 * transient and reset on every session - they are kept here so the UI has a
 * single object to render per row.</p>
 */
public final class StreamDestination {

    private final UUID id;
    private String name;
    private StreamPlatform platform;
    private boolean enabled = true;
    private StreamingCredentials credentials;
    /** {@code null} means "use the global streaming profile". */
    private EncodeProfile profileOverride;

    // ---- runtime, not persisted -------------------------------------------
    private volatile DestinationState state = DestinationState.OFFLINE;
    private volatile String lastError = "";
    private volatile int reconnectAttempts;
    private volatile long liveSinceMillis;

    public StreamDestination(UUID id, String name, StreamPlatform platform, StreamingCredentials credentials) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name == null || name.isBlank() ? platform.displayName() : name.strip();
        this.platform = Objects.requireNonNull(platform, "platform");
        this.credentials = credentials == null ? new StreamingCredentials("", "") : credentials;
    }

    public static StreamDestination create(StreamPlatform platform) {
        return new StreamDestination(UUID.randomUUID(), platform.displayName(), platform,
                new StreamingCredentials(platform.defaultIngestUrl(), ""));
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null || name.isBlank() ? platform.displayName() : name.strip();
    }

    public StreamPlatform platform() {
        return platform;
    }

    public void setPlatform(StreamPlatform platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            state = DestinationState.DISABLED;
        } else if (state == DestinationState.DISABLED) {
            state = DestinationState.OFFLINE;
        }
    }

    public StreamingCredentials credentials() {
        return credentials;
    }

    public void setCredentials(StreamingCredentials credentials) {
        this.credentials = credentials == null ? new StreamingCredentials("", "") : credentials;
    }

    public EncodeProfile profileOverride() {
        return profileOverride;
    }

    public void setProfileOverride(EncodeProfile profileOverride) {
        this.profileOverride = profileOverride;
    }

    /** The profile actually used, falling back to the session-wide one. */
    public EncodeProfile effectiveProfile(EncodeProfile globalProfile) {
        return profileOverride != null ? profileOverride : globalProfile;
    }

    public DestinationState state() {
        return state;
    }

    public void setState(DestinationState state) {
        this.state = state == null ? DestinationState.OFFLINE : state;
        if (this.state == DestinationState.LIVE && liveSinceMillis == 0) {
            liveSinceMillis = System.currentTimeMillis();
        } else if (!this.state.isActive()) {
            liveSinceMillis = 0;
        }
    }

    /** Always store the redacted form: this string can reach logs and the UI. */
    public void setLastError(String error) {
        this.lastError = error == null ? "" : SecretRedactor.redact(error, credentials.streamKey());
    }

    public String lastError() {
        return lastError;
    }

    public int reconnectAttempts() {
        return reconnectAttempts;
    }

    public void setReconnectAttempts(int attempts) {
        this.reconnectAttempts = Math.max(0, attempts);
    }

    public long liveSinceMillis() {
        return liveSinceMillis;
    }

    /** Resets transient state at the start of a session. */
    public void resetRuntime() {
        state = enabled ? DestinationState.OFFLINE : DestinationState.DISABLED;
        lastError = "";
        reconnectAttempts = 0;
        liveSinceMillis = 0;
    }

    /** {@code null} when ready to stream, otherwise a user-facing reason. */
    public String validate() {
        String reason = credentials.validate(platform.keyRequired());
        if (reason != null) {
            return reason;
        }
        if (!dev.streamable.ffmpeg.FFmpegCommandBuilder.isTeeSafe(credentials.publishUrl())) {
            return "Stream URL must not contain '[' or ']'.";
        }
        return null;
    }

    public boolean isReadyToStream() {
        return enabled && validate() == null;
    }

    /** Never includes the stream key. */
    @Override
    public String toString() {
        return "StreamDestination[" + name + ", " + platform + ", " + state + "]";
    }
}
