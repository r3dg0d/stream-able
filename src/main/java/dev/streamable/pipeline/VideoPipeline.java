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
                               OutputCapture.Sink sink) {
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
            this.usesStreamCanvas = usesStreamCanvas;
        }
    }

    private final ProgramCompositor compositor;
    private ActiveOutput recording;
    private ActiveOutput streaming;
    private volatile Stats stats = Stats.EMPTY;
    private final RateMeter renderRate = new RateMeter();
    private long previewUntilNanos;
    private ActiveOutput pendingCloseRecording;
    private ActiveOutput pendingCloseStreaming;

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
        return recording != null || streaming != null;
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
     * @param frozen            use the game snapshot (a Stream-able screen is open)
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
        if (recording == null && streaming == null && !preview) {
            stats = Stats.EMPTY;
            return;
        }
        boolean separate = routingsDiffer && recording != null && streaming != null;
        if (!compositor.ensureCanvas(canvas, separate)) {
            return;
        }
        if (!frozen) {
            compositor.snapshotGame();
        }

        int recordingDue = recording == null ? 0 : recording.pacer.framesDue(now);
        int streamingDue = streaming == null ? 0 : streaming.pacer.framesDue(now);

        boolean composedMain = false;
        boolean composedStream = false;
        // The main canvas serves the recording and the preview (and the stream
        // too, unless its routing differs).
        boolean needMain = recordingDue > 0 || preview || (streamingDue > 0 && !separate);
        if (needMain) {
            Predicate<BrowserSource> include = recording != null
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
        if (streaming != null) {
            if (streamingDue > 0 && (composedStream || composedMain)) {
                streaming.capture.capture(compositor.quadRenderer(), compositor.programTexture(separate),
                        canvas, streaming.config.mode(), streamingDue);
                streaming.captureRate.tick(now);
            }
            streaming.capture.collect(streaming.config.sink());
        }
        stats = new Stats(canvas, renderRate.perSecond(), compositor.composeMillis(),
                outputStats(recording), outputStats(streaming), frozen);
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
    }

    @Override
    public void close() {
        stopRecordingOutput();
        stopStreamingOutput();
        closePending();
    }

    /** Snapshot of video pipeline health for Stream Health and the Video page. */
    public record Stats(Resolution canvas, double renderFps, double composeMillis,
                        OutputStats recording, OutputStats streaming, boolean frozen) {
        public static final Stats EMPTY = new Stats(null, 0, -1, null, null, false);

        public List<OutputStats> outputs() {
            return java.util.stream.Stream.of(recording, streaming).filter(java.util.Objects::nonNull).toList();
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
