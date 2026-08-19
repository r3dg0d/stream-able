package dev.streamable.streaming;

import dev.streamable.StreamAbleLog;
import dev.streamable.audio.LiveAudioSender;
import dev.streamable.ffmpeg.EncodeProfile;
import dev.streamable.ffmpeg.FFmpegCommandBuilder;
import dev.streamable.ffmpeg.FFmpegProcess;

import java.io.IOException;
import java.util.List;

/**
 * One encoder and the destinations it feeds.
 *
 * <p>A group owns a single FFmpeg process. With several destinations sharing an
 * encode profile, FFmpeg's {@code tee} muxer fans the encoded packets out, so
 * three services cost one encode. Each tee slave carries {@code onfail=ignore},
 * which is what lets a dead ingest fail on its own without killing the others.</p>
 *
 * <h2>Failure granularity, honestly stated</h2>
 * <p>With {@code tee}, FFmpeg reports slave failures in its stderr but does not
 * expose a clean per-slave status API. Stream-able therefore tracks
 * per-destination state at two levels: a destination in its own group has fully
 * independent state, while destinations sharing a tee are marked live together
 * and are individually flagged when their URL appears in an error line. If the
 * whole process dies, the group reconnects as a unit. This is a deliberate
 * trade: one encode for N destinations, at the cost of coarser per-slave
 * reporting inside a group.</p>
 */
public final class StreamEncoderGroup implements AutoCloseable {

    private final DestinationGrouping.Group group;
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
        if (!everPublished || currentState() != DestinationState.LIVE) {
            everPublished = true;
            attempts = 0;
            setState(DestinationState.LIVE, "");
            StreamAbleLog.STREAMING.info("Stream is live to {}", group.redactedPublishUrls());
        }
    }

    private DestinationState currentState() {
        return group.destinations().isEmpty()
                ? DestinationState.OFFLINE
                : group.destinations().getFirst().state();
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
     * Flags the specific destinations named in an error line.
     *
     * <p>With a tee fan-out FFmpeg mentions the failing slave URL, so a message
     * can usually be attributed to one destination even though the process is
     * shared.</p>
     */
    private void attributeErrorToDestinations(String errorLine) {
        if (errorLine == null || errorLine.isBlank()) {
            return;
        }
        for (StreamDestination destination : group.destinations()) {
            String ingest = destination.credentials().ingestUrl();
            if (!ingest.isEmpty() && errorLine.contains(ingest)) {
                destination.setLastError(errorLine);
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

    /** Queues a composed frame. Returns false when the frame was dropped. */
    public boolean submitFrame(byte[] frame) {
        FFmpegProcess current = process;
        if (current == null) {
            return false;
        }
        return current.offerFrame(frame) == FFmpegProcess.FrameResult.ACCEPTED;
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
