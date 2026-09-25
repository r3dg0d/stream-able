package dev.streamable.pipeline;

import dev.streamable.StreamAbleLog;
import dev.streamable.browser.BrowserSourceManager;
import dev.streamable.compositor.OutputCapture;
import dev.streamable.compositor.ProgramCompositor;
import dev.streamable.source.BrowserSource;
import dev.streamable.source.SourceList;
import dev.streamable.video.Resolution;
import dev.streamable.video.ScalingMode;

import java.util.List;
import java.util.function.Predicate;

/**
 * Per-frame orchestration of composition and output capture. Render thread only.
 *
 * <pre>
 *   game frame --&gt; ProgramCompositor (canvas) --+--&gt; recording OutputCapture --&gt; recorder
 *                                             +--&gt; streaming OutputCapture --&gt; encoders
 * </pre>
 *
 * <p>Each output has its own {@link FramePacer}, so recording at 60 FPS and
 * streaming at 30 FPS from a 180 Hz game each receive exactly their own rate.
 * The canvas is composed at most once per rendered frame, and only when some
 * output is owed a frame (or the Studio preview is visible).</p>
 */
public final class VideoPipeline implements AutoCloseable {

    /** Everything one output needs; immutable for the life of a session. */
    public record OutputConfig(String name, Resolution resolution, ScalingMode mode, int fps,
                               OutputCapture.Sink sink,
                               java.util.function.Supplier<dev.streamable.config.VideoSettings.Watermark> watermark) {
        /** An output without a watermark. */
        public OutputConfig(String name, Resolution resolution, ScalingMode mode, int fps, OutputCapture.Sink sink) {
            this(name, resolution, mode, fps, sink, null);
        }
    }

    /** A running output: its config, pacer and GPU capture. */
    private static final class ActiveOutput {
        final OutputConfig config;
        final FramePacer pacer;
        final OutputCapture capture;
        final RateMeter captureRate = new RateMeter();
        final boolean usesStreamCanvas;

        ActiveOutput(OutputConfig config, boolean usesStreamCanvas, int maxBuffers) {
            this.config = config;
            this.pacer = new FramePacer(config.fps());
            this.capture = new OutputCapture(config.name(), config.resolution(), maxBuffers);
            this.capture.setWatermark(config.watermark());
            this.usesStreamCanvas = usesStreamCanvas;
        }
    }

    private final ProgramCompositor compositor;
    private ActiveOutput recording;
    private ActiveOutput streaming;
    /** The replay buffer: a recording-like output that encodes into rolling segments. */
    private ActiveOutput replay;
    private volatile Stats stats = Stats.EMPTY;
    private final RateMeter renderRate = new RateMeter();
    private long previewUntilNanos;
    private ActiveOutput pendingCloseRecording;
    private ActiveOutput pendingCloseStreaming;
    private ActiveOutput pendingCloseReplay;

    public VideoPipeline(ProgramCompositor compositor) {
        this.compositor = compositor;
    }

    // ---- session control (client thread == render thread in Minecraft) ----

    public void startRecordingOutput(OutputConfig config) {
        stopRecordingOutput();
        recording = new ActiveOutput(config, false, 6);
        StreamAbleLog.COMPOSITOR.info("Recording output: {} {} at {} FPS", config.resolution().label(),
                config.mode().displayName(), config.fps());
    }

    public void startStreamingOutput(OutputConfig config) {
        stopStreamingOutput();
        streaming = new ActiveOutput(config, true, 6);
        StreamAbleLog.COMPOSITOR.info("Streaming output: {} {} at {} FPS", config.resolution().label(),
                config.mode().displayName(), config.fps());
    }

    public void startReplayOutput(OutputConfig config) {
        stopReplayOutput();
        replay = new ActiveOutput(config, false, 6);
        StreamAbleLog.COMPOSITOR.info("Replay buffer output: {} {} at {} FPS", config.resolution().label(),
                config.mode().displayName(), config.fps());
    }

    public void stopReplayOutput() {
        if (replay != null) {
            pendingCloseReplay = replay;
            replay = null;
        }
    }

    /** Stops capturing. GL objects are released on the next rendered frame. */
    public void stopRecordingOutput() {
        if (recording != null) {
            pendingCloseRecording = recording;
            recording = null;
        }
    }

    public void stopStreamingOutput() {
        if (streaming != null) {
            pendingCloseStreaming = streaming;
            streaming = null;
        }
    }

    public boolean isActive() {
        return recording != null || streaming != null || replay != null;
    }

    /** Keeps the canvas composed for the Studio preview for a short while. */
    public void requestPreview() {
        previewUntilNanos = System.nanoTime() + 250_000_000L;
    }

    public boolean previewRequested(long now) {
        return now < previewUntilNanos;
    }

    public Stats stats() {
        return stats;
    }

    /**
     * Runs one frame of the pipeline.
     *
     * @param canvas            the program canvas size
     * @param gameScaling       how the game frame fills the canvas
     * @param frozen            use the game snapshot (a Stream-able screen is open); the caller keeps
     *                          the snapshot current, taken before any local overlay is drawn
     * @param includeRecording  routing filter for the recording canvas
     * @param includeStream     routing filter for the stream canvas
     * @param routingsDiffer    whether any visible source is routed differently to the two outputs
     */
    public void onFrame(SourceList sources, BrowserSourceManager browsers, Resolution canvas,
                        ScalingMode gameScaling, boolean frozen,
                        Predicate<BrowserSource> includeRecording, Predicate<BrowserSource> includeStream,
                        boolean routingsDiffer) {
        closePending();
        long now = System.nanoTime();
        renderRate.tick(now);
        boolean preview = previewRequested(now);
        if (recording == null && streaming == null && replay == null && !preview) {
            stats = Stats.EMPTY;
            return;
        }
        boolean recordingLike = recording != null || replay != null;
        boolean separate = routingsDiffer && recordingLike && streaming != null;
        if (!compositor.ensureCanvas(canvas, separate)) {
            return;
        }

        int recordingDue = recording == null ? 0 : recording.pacer.framesDue(now);
        int streamingDue = streaming == null ? 0 : streaming.pacer.framesDue(now);
        int replayDue = replay == null ? 0 : replay.pacer.framesDue(now);

        boolean composedMain = false;
        boolean composedStream = false;
        // The main canvas serves the recording and the preview (and the stream
        // too, unless its routing differs).
        boolean needMain = recordingDue > 0 || replayDue > 0 || preview || (streamingDue > 0 && !separate);
        if (needMain) {
            Predicate<BrowserSource> include = recordingLike
                    ? (separate || streaming == null ? includeRecording : includeRecording.or(includeStream))
                    : includeStream;
            compositor.composeProgramFrame(sources, browsers, include, false, gameScaling, frozen);
            composedMain = true;
        }
        if (streamingDue > 0 && separate) {
            compositor.composeProgramFrame(sources, browsers, includeStream, true, gameScaling, frozen);
            composedStream = true;
        }

        if (recording != null) {
            if (recordingDue > 0 && composedMain) {
                recording.capture.capture(compositor.quadRenderer(), compositor.programTexture(false),
                        canvas, recording.config.mode(), recordingDue);
                recording.captureRate.tick(now);
            }
            recording.capture.collect(recording.config.sink());
        }
        if (replay != null) {
            if (replayDue > 0 && composedMain) {
                replay.capture.capture(compositor.quadRenderer(), compositor.programTexture(false),
                        canvas, replay.config.mode(), replayDue);
                replay.captureRate.tick(now);
            }
            replay.capture.collect(replay.config.sink());
        }
        if (streaming != null) {
            if (streamingDue > 0 && (composedStream || composedMain)) {
                streaming.capture.capture(compositor.quadRenderer(), compositor.programTexture(separate),
                        canvas, streaming.config.mode(), streamingDue);
                streaming.captureRate.tick(now);
            }
            streaming.capture.collect(streaming.config.sink());
        }
        stats = new Stats(canvas, renderRate.perSecond(), compositor.composeMillis(),
                outputStats(recording), outputStats(streaming), outputStats(replay), frozen);
    }

    private static OutputStats outputStats(ActiveOutput output) {
        if (output == null) {
            return null;
        }
        OutputCapture capture = output.capture;
        return new OutputStats(output.config.name(), output.config.resolution(), output.config.mode(),
                output.config.fps(), output.captureRate.perSecond(), capture.averageReadbackMillis(),
                output.pacer.duplicatedFrames(), output.pacer.largestCatchUp(),
                capture.skippedCaptures(), capture.poolExhausted(), capture.memoryBytes(),
                capture.isBroken());
    }

    private void closePending() {
        if (pendingCloseRecording != null) {
            pendingCloseRecording.capture.close();
            pendingCloseRecording = null;
        }
        if (pendingCloseStreaming != null) {
            pendingCloseStreaming.capture.close();
            pendingCloseStreaming = null;
        }
        if (pendingCloseReplay != null) {
            pendingCloseReplay.capture.close();
            pendingCloseReplay = null;
        }
    }

    @Override
    public void close() {
        stopRecordingOutput();
        stopStreamingOutput();
        stopReplayOutput();
        closePending();
    }

    /** Snapshot of video pipeline health for Stream Health and the Video page. */
    public record Stats(Resolution canvas, double renderFps, double composeMillis,
                        OutputStats recording, OutputStats streaming, OutputStats replay, boolean frozen) {
        public static final Stats EMPTY = new Stats(null, 0, -1, null, null, null, false);

        public List<OutputStats> outputs() {
            return java.util.stream.Stream.of(recording, streaming, replay).filter(java.util.Objects::nonNull).toList();
        }
    }

    /**
     * @param captureFps       measured rate captures were started at
     * @param readbackMillis   average time copying finished frames out of GPU memory
     * @param renderRepeats    frames repeated because the game rendered slower than the output
     * @param largestCatchUp   largest single repeat burst (a stall)
     * @param readbackSkipped  captures skipped because three GPU readbacks were in flight
     * @param bufferExhausted  pictures lost because every buffer was waiting for the encoder
     */
    public record OutputStats(String name, Resolution resolution, ScalingMode mode, int targetFps,
                              double captureFps, double readbackMillis, long renderRepeats, long largestCatchUp,
                              long readbackSkipped, long bufferExhausted, long memoryBytes, boolean broken) {
    }
}
