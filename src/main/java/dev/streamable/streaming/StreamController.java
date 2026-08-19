package dev.streamable.streaming;

import dev.streamable.StreamAbleLog;
import dev.streamable.config.StreamingSettings;
import dev.streamable.ffmpeg.EncodeProfile;
import dev.streamable.ffmpeg.FFmpegCapabilityProbe;
import dev.streamable.ffmpeg.FFmpegManager;
import dev.streamable.ffmpeg.VideoEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The broadcast state machine.
 *
 * <p>Independent from recording by design: stopping the local recording leaves
 * the stream running, and a stream failure never touches the file on disk. The
 * only thing the two share is the composed program frame.</p>
 *
 * <p>Destinations are partitioned into {@link StreamEncoderGroup}s by encode
 * profile, so compatible destinations share one encoder and one encode. Each
 * group fails, retries and recovers on its own - a dead Kick ingest does not
 * take Twitch and YouTube offline.</p>
 *
 * <p><b>Threading:</b> {@link #submitFrame} is called from the render thread and
 * must never block; everything it touches is a bounded queue. Lifecycle methods
 * are called from the client thread.</p>
 */
public final class StreamController {

    /** Overall session state, distinct from any one destination's state. */
    public enum State { IDLE, STARTING, LIVE, STOPPING }

    private final FFmpegManager ffmpeg;
    private final FFmpegCapabilityProbe probe;
    private final List<StreamEncoderGroup> groups = new ArrayList<>();
    private final List<StreamDestination> destinations = new ArrayList<>();

    private volatile State state = State.IDLE;
    private volatile long startedAtMillis;
    private volatile String lastError = "";
    private VideoEncoder activeEncoder = VideoEncoder.X264;
    private EncodeProfile activeProfile;
    private boolean audioEnabled = true;
    private boolean multipleEncodersWarned;

    public StreamController(FFmpegManager ffmpeg, FFmpegCapabilityProbe probe) {
        this.ffmpeg = ffmpeg;
        this.probe = probe;
    }

    public State state() {
        return state;
    }

    public boolean isLive() {
        return state == State.LIVE;
    }

    public String lastError() {
        return lastError;
    }

    public List<StreamDestination> destinations() {
        return List.copyOf(destinations);
    }

    public void setDestinations(List<StreamDestination> updated) {
        destinations.clear();
        destinations.addAll(updated);
    }

    public VideoEncoder activeEncoder() {
        return activeEncoder;
    }

    /**
     * Validates the configuration before anything is started.
     *
     * @return {@code null} when ready, otherwise a message to show the user
     */
    public String validate(StreamingSettings settings) {
        if (!ffmpeg.isAvailable()) {
            return "FFmpeg is not available. Install it from the Stream-able settings first.";
        }
        List<StreamDestination> enabled = destinations.stream().filter(StreamDestination::enabled).toList();
        if (enabled.isEmpty()) {
            return "No streaming destination is enabled.";
        }
        for (StreamDestination destination : enabled) {
            String reason = destination.validate();
            if (reason != null) {
                return destination.name() + ": " + reason;
            }
        }
        if (settings.bitrateKbps < 100) {
            return "Video bitrate is too low.";
        }
        return null;
    }

    /**
     * Goes live.
     *
     * @return {@code null} on success, or a user-facing error message
     */
    public String start(StreamingSettings settings, boolean withAudio,
                        int sourceWidth, int sourceHeight) {
        if (state != State.IDLE) {
            return "A stream is already running.";
        }
        String invalid = validate(settings);
        if (invalid != null) {
            lastError = invalid;
            return invalid;
        }
        state = State.STARTING;
        audioEnabled = withAudio;
        multipleEncodersWarned = false;
        destinations.forEach(StreamDestination::resetRuntime);

        try {
            // Only H.264 is universally accepted by RTMP ingests, so encoder
            // auto-detection is restricted to stream-safe codecs here.
            activeEncoder = probe.resolve(settings.encoder, true);
            activeProfile = settings.encodeProfile(activeEncoder);

            List<DestinationGrouping.Group> planned =
                    DestinationGrouping.group(destinations, activeProfile);
            if (planned.isEmpty()) {
                state = State.IDLE;
                lastError = "No destination passed validation.";
                return lastError;
            }
            if (DestinationGrouping.requiresMultipleEncoders(planned)) {
                multipleEncodersWarned = true;
                StreamAbleLog.STREAMING.warn(
                        "{} destinations use incompatible output profiles, so {} separate encoders will run. "
                                + "This costs additional CPU/GPU.",
                        destinations.size(), planned.size());
            }

            List<String> failures = new ArrayList<>();
            for (DestinationGrouping.Group group : planned) {
                StreamEncoderGroup encoderGroup = new StreamEncoderGroup(
                        group, ffmpeg.executable(), settings.frameQueueCapacity,
                        settings.reconnectPolicy(), sourceWidth, sourceHeight);
                String error = encoderGroup.start(withAudio);
                if (error != null) {
                    failures.add(error);
                }
                groups.add(encoderGroup);
            }

            if (groups.stream().noneMatch(StreamEncoderGroup::isRunning)) {
                lastError = failures.isEmpty() ? "No encoder could be started." : failures.getFirst();
                stop();
                return lastError;
            }

            startedAtMillis = System.currentTimeMillis();
            state = State.LIVE;
            lastError = "";
            StreamAbleLog.STREAMING.info("Stream started with {} encoder group(s) using {}.",
                    groups.size(), activeEncoder.displayName());
            return null;
        } catch (RuntimeException e) {
            lastError = "Could not start the stream: " + e.getMessage();
            StreamAbleLog.STREAMING.error("Failed to start streaming", e);
            stop();
            return lastError;
        }
    }

    /** Stops every destination and releases the encoders. */
    public void stop() {
        if (state == State.IDLE) {
            return;
        }
        state = State.STOPPING;
        for (StreamEncoderGroup group : groups) {
            try {
                group.close();
            } catch (RuntimeException e) {
                StreamAbleLog.STREAMING.warn("Error stopping an encoder group", e);
            }
        }
        groups.clear();
        startedAtMillis = 0;
        state = State.IDLE;
        StreamAbleLog.STREAMING.info("Stream stopped.");
    }

    /**
     * Hands the composed frame to every running encoder.
     *
     * <p>Called on the render thread: it only ever performs a bounded, non-blocking
     * queue offer per group.</p>
     */
    public void submitFrame(byte[] frame) {
        if (state != State.LIVE || frame == null) {
            return;
        }
        for (StreamEncoderGroup group : groups) {
            group.submitFrame(frame);
        }
    }

    /** Hands a PCM chunk of the program mix to every running encoder. */
    public void submitAudio(byte[] pcm) {
        if (state != State.LIVE || pcm == null) {
            return;
        }
        for (StreamEncoderGroup group : groups) {
            group.submitAudio(pcm);
        }
    }

    /**
     * Promotes groups to LIVE once they confirm publishing, and drives reconnect
     * backoff. Call once per client tick.
     */
    public void tick() {
        if (state != State.LIVE) {
            return;
        }
        for (StreamEncoderGroup group : groups) {
            group.tick();
            if (group.isReadyToRetry()) {
                String error = group.retry(audioEnabled);
                if (error != null) {
                    StreamAbleLog.STREAMING.warn("Reconnect attempt failed: {}", error);
                }
            }
        }
        // Surface a failed destination's reason so the Studio and the HUD show
        // what went wrong rather than an endless "Reconnecting".
        for (StreamDestination destination : destinations) {
            if (destination.state() == DestinationState.ERROR && !destination.lastError().isEmpty()) {
                lastError = destination.lastError();
                break;
            }
        }
    }

    /** True when at least one destination has confirmed it is publishing. */
    public boolean hasLiveDestination() {
        return destinations.stream().anyMatch(d -> d.state() == DestinationState.LIVE);
    }

    /** True when every enabled destination has failed - the session is dead. */
    public boolean allDestinationsFailed() {
        List<StreamDestination> active = destinations.stream()
                .filter(StreamDestination::enabled).toList();
        return !active.isEmpty()
                && active.stream().allMatch(d -> d.state() == DestinationState.ERROR);
    }

    /** Restarts one destination's group without disturbing the others. */
    public boolean reconnectDestination(UUID destinationId) {
        for (StreamEncoderGroup group : groups) {
            if (group.destinations().stream().anyMatch(d -> d.id().equals(destinationId))) {
                group.close();
                return group.retry(audioEnabled) == null;
            }
        }
        return false;
    }

    /** Takes one destination offline, leaving healthy ones live. */
    public boolean disableDestination(UUID destinationId) {
        for (StreamEncoderGroup group : groups) {
            for (StreamDestination destination : group.destinations()) {
                if (destination.id().equals(destinationId)) {
                    destination.setEnabled(false);
                    // A destination inside a shared tee cannot be removed from a
                    // running FFmpeg process, so only a group it owns alone is
                    // stopped; otherwise it goes offline at the next restart.
                    if (group.destinations().size() == 1) {
                        group.close();
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** Redacted diagnostics for the destination's Copy Diagnostic action. */
    public String diagnosticsFor(UUID destinationId) {
        for (StreamEncoderGroup group : groups) {
            if (group.destinations().stream().anyMatch(d -> d.id().equals(destinationId))) {
                return group.diagnostics();
            }
        }
        return "";
    }

    public boolean usesMultipleEncoders() {
        return multipleEncodersWarned;
    }

    /** Estimated upload requirement for the current plan. */
    public BandwidthEstimator.Estimate bandwidthEstimate(StreamingSettings settings) {
        EncodeProfile profile = activeProfile != null
                ? activeProfile
                : settings.encodeProfile(activeEncoder);
        List<DestinationGrouping.Group> planned = groups.isEmpty()
                ? DestinationGrouping.group(destinations, profile)
                : groups.stream().map(StreamEncoderGroup::group).toList();
        return BandwidthEstimator.estimate(planned.stream()
                .map(g -> new BandwidthEstimator.GroupLoad(g.profile(), g.destinations().size()))
                .toList());
    }

    /** Consistent snapshot for the HUD and Studio screen. */
    public StreamHealth health() {
        if (state != State.LIVE) {
            return StreamHealth.OFFLINE;
        }
        long submitted = 0;
        long dropped = 0;
        double pressure = 0;
        List<StreamHealth.DestinationStatus> statuses = new ArrayList<>();
        for (StreamEncoderGroup group : groups) {
            submitted += group.framesSubmitted();
            dropped += group.framesDropped();
            pressure = Math.max(pressure, group.queuePressure());
            for (StreamDestination destination : group.destinations()) {
                statuses.add(new StreamHealth.DestinationStatus(
                        destination.name(), destination.state(), destination.lastError()));
            }
        }
        EncodeProfile profile = activeProfile;
        return new StreamHealth(
                true,
                System.currentTimeMillis() - startedAtMillis,
                profile == null ? 0 : profile.video().bitrateKbps(),
                profile == null ? 0 : profile.video().fps(),
                submitted,
                dropped,
                pressure,
                activeEncoder.displayName(),
                List.copyOf(statuses));
    }
}
