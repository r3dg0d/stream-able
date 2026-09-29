package dev.streamable.streaming;

import dev.streamable.StreamAbleLog;
import dev.streamable.audio.LiveAudioSender;
import dev.streamable.ffmpeg.EncodeProfile;
import dev.streamable.ffmpeg.FFmpegCommandBuilder;
import dev.streamable.ffmpeg.FFmpegProcess;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One encoder and the destinations it feeds.
 *
 * <p>A group owns a single FFmpeg process. With several destinations sharing an
 * encode profile, FFmpeg's {@code tee} muxer fans the encoded packets out, so
 * three services cost one encode. Each tee slave carries {@code onfail=ignore},
 * which is what lets a dead ingest fail on its own without killing the others.</p>
 *
 * <h2>Failure granularity</h2>
 * <p>With {@code tee}, FFmpeg reports slave failures on stderr but has no clean
 * per-slave status API. Stream-able attributes those lines with
 * {@link TeeSlaveAttributor}: a destination whose URL appears in an error is
 * marked {@link DestinationState#ERROR} while healthy siblings stay
 * {@link DestinationState#LIVE}. If the whole process dies, the group reconnects
 * as a unit. Mid-stream slave drops therefore surface per row in Stream Health;
 * a full process death is still a shared reconnect.</p>
 *
 * <p>Failed tee rows are recovered onto a dedicated encoder via
 * {@link #splitOffForRecovery(java.util.UUID)} when siblings are still live:
 * automatically on the session {@link ReconnectPolicy} backoff, or immediately
 * from Destinations → Reconnect. Healthy slaves are not restarted.</p>
 */
public final class StreamEncoderGroup implements AutoCloseable {

    private DestinationGrouping.Group group;
    private final String ffmpegExecutable;
    private final int queueCapacity;
    private final ReconnectPolicy reconnectPolicy;
    /** Size of the frames the compositor actually delivers. */
    private final int sourceWidth;
    private final int sourceHeight;

    /**
     * How many times to retry when FFmpeg has never managed to publish.
     *
     * <p>A stream that fails at the handshake fails for a reason that will not
     * change on its own - a wrong key, a malformed ingest URL, a stream not
     * enabled on the dashboard. Retrying it thirty times just hides the error,
     * so startup failures get a couple of attempts and then report what actually
     * went wrong.</p>
     */
    private static final int MAX_STARTUP_ATTEMPTS = 3;

    private FFmpegProcess process;
    private LiveAudioSender audioSender;
    private int attempts;
    /** True once this group has actually published; distinguishes drops from bad config. */
    private boolean everPublished;
    private volatile long nextRetryAtMillis;
    private volatile boolean stopped;
    /**
     * Destinations flagged by a mid-stream tee slave error while the process
     * kept running. {@link #tick()} must not promote these back to LIVE.
     */
    private final Set<UUID> teeFailedIds = new HashSet<>();
    /** When a mid-stream tee slave should be split onto a dedicated encoder. */
    private final Map<UUID, Long> teeRecoveryAtMillis = new HashMap<>();
    /** 1-based recovery attempts already scheduled for each tee-failed id. */
    private final Map<UUID, Integer> teeRecoveryAttempts = new HashMap<>();

    public StreamEncoderGroup(DestinationGrouping.Group group, String ffmpegExecutable,
                              int queueCapacity, ReconnectPolicy reconnectPolicy,
                              int sourceWidth, int sourceHeight) {
        this.group = group;
        this.ffmpegExecutable = ffmpegExecutable;
        this.queueCapacity = queueCapacity;
        this.reconnectPolicy = reconnectPolicy;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
    }

    public DestinationGrouping.Group group() {
        return group;
    }

    public EncodeProfile profile() {
        return group.profile();
    }

    public List<StreamDestination> destinations() {
        return group.destinations();
    }

    public boolean isRunning() {
        return process != null && process.isRunning();
    }

    /** The audio channel FFmpeg reads from, or {@code null} when audio is off. */
    public LiveAudioSender audioSender() {
        return audioSender;
    }

    /**
     * Starts the encoder.
     *
     * @param withAudio when true a loopback audio channel is opened first
     * @return {@code null} on success, or a user-facing error message
     */
    public String start(boolean withAudio) {
        if (isRunning()) {
            return null;
        }
        setState(DestinationState.CONNECTING, "");
        int audioPort = 0;
        try {
            if (withAudio) {
                audioSender = new LiveAudioSender();
                audioPort = audioSender.port();
            }
            List<String> command = FFmpegCommandBuilder.buildStreamCommand(
                    ffmpegExecutable, group.profile(), sourceWidth, sourceHeight,
                    group.publishUrls(), audioPort, 0.0);

            String[] secrets = group.destinations().stream()
                    .map(d -> d.credentials().streamKey())
                    .filter(k -> !k.isEmpty())
                    .toArray(String[]::new);

            process = new FFmpegProcess(command, queueCapacity, secrets);
            process.setOnUnexpectedExit(this::onProcessDied);
            process.setOnErrorLine(this::onErrorLine);
            teeFailedIds.clear();
            teeRecoveryAtMillis.clear();
            teeRecoveryAttempts.clear();
            process.start();
            // Deliberately still CONNECTING: a spawned process proves nothing.
            // FFmpeg can start, fail the RTMP handshake and exit a second later,
            // and reporting LIVE here is what makes a broken stream look healthy.
            // tick() promotes to LIVE once FFmpeg reports real progress.
            setState(DestinationState.CONNECTING, "");
            StreamAbleLog.STREAMING.info("Connecting to {} destination(s): {}",
                    group.destinations().size(), group.redactedPublishUrls());
            return null;
        } catch (IOException | RuntimeException e) {
            String message = "Could not start the encoder: " + e.getMessage();
            setState(DestinationState.ERROR, message);
            StreamAbleLog.STREAMING.error("Failed to start streaming encoder", e);
            closeAudio();
            return message;
        }
    }

    /**
     * Promotes the group to LIVE once FFmpeg confirms it is publishing.
     *
     * <p>This is also where the backoff counter is reset: a stream that has
     * genuinely published is healthy, and its next failure should start from a
     * short delay again. Resetting on <em>spawn</em> instead - which an earlier
     * version did - meant a stream that never connected retried forever,
     * reporting "Attempt 1/30" every single time.</p>
     */
    public void tick() {
        FFmpegProcess current = process;
        if (stopped || current == null || !current.isPublishing()) {
            return;
        }
        boolean anyPromoted = false;
        for (StreamDestination destination : group.destinations()) {
            if (teeFailedIds.contains(destination.id())) {
                continue;
            }
            if (destination.state() != DestinationState.LIVE) {
                destination.setState(DestinationState.LIVE);
                anyPromoted = true;
            }
        }
        if (!everPublished || anyPromoted) {
            everPublished = true;
            attempts = 0;
            StreamAbleLog.STREAMING.info("Stream is live to {}", group.redactedPublishUrls());
        }
    }

    private void onProcessDied() {
        if (stopped) {
            return;
        }
        String detail = process == null ? "" : process.lastError();
        attributeErrorToDestinations(detail);
        attempts++;

        // A group that never published is misconfigured, not merely disconnected.
        // Retrying it on the normal schedule would loop forever without ever
        // telling the user what is wrong.
        boolean startupFailure = !everPublished;
        int limit = startupFailure ? MAX_STARTUP_ATTEMPTS : Integer.MAX_VALUE;
        boolean mayRetry = attempts < limit && reconnectPolicy.shouldRetry(attempts - 1);

        if (mayRetry) {
            long delay = reconnectPolicy.delayForAttempt(attempts);
            nextRetryAtMillis = System.currentTimeMillis() + delay;
            setState(DestinationState.RECONNECTING,
                    (startupFailure ? "Could not connect. Retrying in " : "Connection lost. Retrying in ")
                            + (delay / 1000) + " seconds... " + reconnectPolicy.describeAttempt(attempts));
            StreamAbleLog.STREAMING.warn("Stream group {}; retrying in {} ms ({})",
                    startupFailure ? "failed to connect" : "dropped", delay,
                    reconnectPolicy.describeAttempt(attempts));
        } else {
            nextRetryAtMillis = 0;
            setState(DestinationState.ERROR, startupFailure
                    ? explainStartupFailure(detail)
                    : (detail.isEmpty() ? "Connection lost and retries are exhausted." : detail));
            StreamAbleLog.STREAMING.error("Stream group gave up after {} attempt(s): {}",
                    attempts, detail.isEmpty() ? "no error reported" : detail);
        }
        closeAudio();
    }

    /**
     * Turns FFmpeg's terse handshake failure into something a user can act on.
     *
     * <p>"Error opening output ... Input/output error" is what FFmpeg reports for
     * essentially every rejected RTMP publish, so the message has to enumerate
     * the realistic causes rather than repeat it verbatim.</p>
     */
    private String explainStartupFailure(String detail) {
        StringBuilder message = new StringBuilder("Could not start the broadcast. ");
        if (detail.toLowerCase(java.util.Locale.ROOT).contains("i/o error")
                || detail.toLowerCase(java.util.Locale.ROOT).contains("input/output error")
                || detail.toLowerCase(java.util.Locale.ROOT).contains("error opening output")) {
            message.append("The server rejected the connection. Check that the stream key is "
                    + "current, that the Stream URL includes the full ingest path "
                    + "(for example rtmp://host/live2 or rtmps://host:443/app), and that the "
                    + "stream is enabled on the service's dashboard.");
        } else if (detail.isEmpty()) {
            message.append("FFmpeg exited without reporting a reason.");
        } else {
            message.append(detail);
        }
        return message.toString();
    }

    /**
     * Live stderr hook: a tee slave can fail while {@code onfail=ignore} keeps
     * the encoder running. Attribute the line and leave healthy siblings live.
     */
    /** Live stderr hook (package-visible for offline recovery tests). */
    void onErrorLine(String errorLine) {
        int flagged = noteTeeSlaveFailures(errorLine);
        if (flagged > 0) {
            scheduleTeeRecoveries(System.currentTimeMillis());
        }
    }

    /**
     * Attributes a stderr line onto destinations and records tee-failed ids.
     * Does not schedule recovery (tests drive {@link #scheduleTeeRecoveries(long)}
     * with a fixed clock).
     *
     * @return how many destinations were flagged
     */
    int noteTeeSlaveFailures(String errorLine) {
        if (stopped) {
            return 0;
        }
        int flagged = TeeSlaveAttributor.flagFailedSlaves(errorLine, group.destinations());
        if (flagged <= 0) {
            return 0;
        }
        for (StreamDestination destination : group.destinations()) {
            if (destination.state() == DestinationState.ERROR) {
                teeFailedIds.add(destination.id());
            }
        }
        StreamAbleLog.STREAMING.warn(
                "Attributed ingest error to {} destination(s) without stopping the shared encoder",
                flagged);
        return flagged;
    }

    /**
     * Schedules dedicated-encoder recovery for newly failed tee slaves that still
     * have a {@link DestinationState#LIVE} sibling, using {@link ReconnectPolicy}.
     */
    void scheduleTeeRecoveries(long nowMillis) {
        if (stopped || !reconnectPolicy.enabled()) {
            return;
        }
        for (StreamDestination destination : group.destinations()) {
            UUID id = destination.id();
            if (!teeFailedIds.contains(id) || destination.state() != DestinationState.ERROR) {
                continue;
            }
            if (teeRecoveryAtMillis.containsKey(id)) {
                continue;
            }
            if (!TeeRecovery.shouldSplitOff(destination, group.destinations())) {
                continue;
            }
            int soFar = teeRecoveryAttempts.getOrDefault(id, 0);
            if (!reconnectPolicy.shouldRetry(soFar)) {
                destination.setLastError(
                        "Ingest failed and dedicated-encoder recovery is exhausted after "
                                + soFar + " attempt(s). Use Destinations → Reconnect to try again.");
                StreamAbleLog.STREAMING.error(
                        "Tee slave recovery exhausted for {} after {} attempt(s)",
                        destination.name(), soFar);
                continue;
            }
            int attempt = soFar + 1;
            teeRecoveryAttempts.put(id, attempt);
            long delay = reconnectPolicy.delayForAttempt(attempt);
            teeRecoveryAtMillis.put(id, nowMillis + delay);
            destination.setState(DestinationState.RECONNECTING);
            destination.setReconnectAttempts(attempt);
            destination.setLastError(
                    "Ingest failed. Recovering on a dedicated encoder in "
                            + (delay / 1000) + " seconds... "
                            + reconnectPolicy.describeAttempt(attempt));
            StreamAbleLog.STREAMING.warn(
                    "Scheduling tee slave recovery for {} in {} ms ({})",
                    destination.name(), delay, reconnectPolicy.describeAttempt(attempt));
        }
    }

    /**
     * Destinations whose scheduled tee recovery is due. Removes them from the
     * schedule map; the controller should call
     * {@link StreamController#reconnectDestination(UUID)} for each.
     */
    public List<UUID> pollReadyTeeRecoveries() {
        return pollReadyTeeRecoveries(System.currentTimeMillis());
    }

    List<UUID> pollReadyTeeRecoveries(long nowMillis) {
        if (stopped || teeRecoveryAtMillis.isEmpty()) {
            return List.of();
        }
        List<UUID> ready = new ArrayList<>();
        List<UUID> cancel = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : teeRecoveryAtMillis.entrySet()) {
            UUID id = entry.getKey();
            StreamDestination target = destinationById(id);
            // Drop schedules the user cancelled (disabled) or that are no longer
            // eligible to split (sibling gone, state changed) so they do not stick.
            if (!TeeRecovery.shouldSplitOff(target, group.destinations())) {
                cancel.add(id);
                if (target != null
                        && target.enabled()
                        && target.state() == DestinationState.RECONNECTING) {
                    target.setState(DestinationState.ERROR);
                    target.setLastError(
                            "Ingest failed. Automatic dedicated-encoder recovery cancelled "
                                    + "(no healthy sibling left on the shared tee). "
                                    + "Use Destinations → Reconnect.");
                }
                continue;
            }
            Long at = entry.getValue();
            if (at == null || at <= 0 || nowMillis < at) {
                continue;
            }
            ready.add(id);
        }
        for (UUID id : cancel) {
            cancelTeeRecovery(id);
        }
        for (UUID id : ready) {
            teeRecoveryAtMillis.remove(id);
        }
        return List.copyOf(ready);
    }

    /**
     * Cancels a pending automatic tee recovery for {@code destinationId} without
     * changing destination state (caller may already have set DISABLED / ERROR).
     */
    public boolean cancelTeeRecovery(UUID destinationId) {
        if (destinationId == null) {
            return false;
        }
        boolean removed = teeRecoveryAtMillis.remove(destinationId) != null;
        // Keep attempt counters so a later re-enable still respects maxAttempts
        // for this session; restart()/start() clear them for a fresh session.
        return removed;
    }

    private StreamDestination destinationById(UUID id) {
        for (StreamDestination destination : group.destinations()) {
            if (destination.id().equals(id)) {
                return destination;
            }
        }
        return null;
    }

    /**
     * On process death, attach the error detail to any destination named in it
     * (state is then overwritten by the reconnect / error transition).
     */
    private void attributeErrorToDestinations(String errorLine) {
        TeeSlaveAttributor.flagFailedSlaves(errorLine, group.destinations());
        for (StreamDestination destination : group.destinations()) {
            if (destination.state() == DestinationState.ERROR) {
                teeFailedIds.add(destination.id());
            }
        }
    }

    /** Whether the backoff has elapsed and a retry should be attempted. */
    public boolean isReadyToRetry() {
        return !stopped
                && !isRunning()
                && nextRetryAtMillis > 0
                && System.currentTimeMillis() >= nextRetryAtMillis;
    }

    public String retry(boolean withAudio) {
        nextRetryAtMillis = 0;
        return start(withAudio);
    }

    /**
     * Manual reconnect from the UI: tears the process down and starts again
     * immediately. Unlike {@link #close()} this leaves the group active, so
     * it is promoted to LIVE and retried on failure as normal.
     */
    public String restart(boolean withAudio) {
        FFmpegProcess current = process;
        stopped = true;             // suppress the exit callback while we stop it
        if (current != null) {
            current.stop();
        }
        process = null;
        closeAudio();
        stopped = false;
        attempts = 0;
        nextRetryAtMillis = 0;
        teeFailedIds.clear();
        teeRecoveryAtMillis.clear();
        teeRecoveryAttempts.clear();
        return start(withAudio);
    }

    /** Queues a captured frame standing for {@code repeat} output frames. */
    public boolean submitFrame(dev.streamable.pipeline.PooledFrame frame, int repeat) {
        FFmpegProcess current = process;
        if (current == null) {
            return false;
        }
        return current.offerFrame(frame, repeat) == FFmpegProcess.FrameResult.ACCEPTED;
    }

    public FFmpegProcess process() {
        return process;
    }

    public int attempts() {
        return attempts;
    }

    public void submitAudio(byte[] pcm) {
        LiveAudioSender sender = audioSender;
        if (sender != null) {
            sender.write(pcm);
        }
    }

    public long framesSubmitted() {
        return process == null ? 0 : process.framesWritten();
    }

    public long framesDropped() {
        return process == null ? 0 : process.framesDropped();
    }

    public double queuePressure() {
        return process == null ? 0 : process.queuePressure();
    }

    /**
     * Removes a destination from this group without stopping the shared process.
     *
     * <p>Used when a tee slave has already failed ({@code onfail=ignore}) and is
     * about to be recovered on a dedicated encoder. The dead slave stays in the
     * running FFmpeg tee (harmless); only Stream-able's bookkeeping moves.</p>
     *
     * @return {@code true} when the destination was present and removed
     */
    public boolean detachDestination(UUID destinationId) {
        List<StreamDestination> current = group.destinations();
        List<StreamDestination> remaining = current.stream()
                .filter(destination -> !destination.id().equals(destinationId))
                .toList();
        if (remaining.size() == current.size()) {
            return false;
        }
        teeFailedIds.remove(destinationId);
        teeRecoveryAtMillis.remove(destinationId);
        teeRecoveryAttempts.remove(destinationId);
        group = new DestinationGrouping.Group(group.profile(), remaining);
        return true;
    }

    /**
     * Detaches a failed tee destination and returns a new solo group ready to
     * {@link #start(boolean)}, leaving this group's process and healthy siblings
     * untouched.
     *
     * @return the solo recovery group, or {@code null} when split-off is not appropriate
     */
    public StreamEncoderGroup splitOffForRecovery(UUID destinationId) {
        StreamDestination target = null;
        for (StreamDestination destination : group.destinations()) {
            if (destination.id().equals(destinationId)) {
                target = destination;
                break;
            }
        }
        if (!TeeRecovery.shouldSplitOff(target, group.destinations())) {
            return null;
        }
        if (!detachDestination(destinationId)) {
            return null;
        }
        DestinationGrouping.Group solo = new DestinationGrouping.Group(group.profile(), List.of(target));
        StreamAbleLog.STREAMING.info(
                "Recovering {} on a dedicated encoder; {} sibling(s) stay on the shared tee",
                target.name(), group.destinations().size());
        return new StreamEncoderGroup(solo, ffmpegExecutable, queueCapacity, reconnectPolicy,
                sourceWidth, sourceHeight);
    }

    /** Recent FFmpeg output with credentials removed. */
    public String diagnostics() {
        return process == null ? "" : process.diagnostics();
    }

    private void setState(DestinationState state, String detail) {
        for (StreamDestination destination : group.destinations()) {
            destination.setState(state);
            if (!detail.isEmpty()) {
                destination.setLastError(detail);
            }
            destination.setReconnectAttempts(attempts);
        }
    }

    private void closeAudio() {
        LiveAudioSender sender = audioSender;
        if (sender != null) {
            sender.close();
            audioSender = null;
        }
    }

    /** Stops this group without touching any other. */
    @Override
    public void close() {
        stopped = true;
        if (process != null) {
            process.stop();
            process = null;
        }
        closeAudio();
        setState(DestinationState.OFFLINE, "");
    }
}
