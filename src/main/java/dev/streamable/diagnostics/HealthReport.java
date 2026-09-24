package dev.streamable.diagnostics;

import dev.streamable.audio.ai.NoiseCancellationManager;
import dev.streamable.audio.mic.MicrophoneProcessor;
import dev.streamable.pipeline.VideoPipeline;
import dev.streamable.streaming.StreamHealth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stream Health: measured numbers, each with a short explanation, plus
 * findings that say what the numbers mean.
 *
 * <p>Pure: built from snapshots, so it is unit-tested and the UI only renders
 * it. Where a figure cannot be measured on this pipeline it is reported as
 * unavailable rather than invented (encoder utilisation is not exposed by
 * FFmpeg for most encoders, for example).</p>
 */
public record HealthReport(List<Metric> metrics, List<Finding> findings, Condition network) {

    public enum Severity { OK, INFO, WARNING, CRITICAL }

    public enum Condition {
        OFFLINE("Offline"), GOOD("Good"), FAIR("Fair"), POOR("Poor");

        private final String label;

        Condition(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Metric(String group, String name, String value, String explanation, Severity severity) {
    }

    public record Finding(Severity severity, String message) {
    }

    /** Inputs gathered by the runtime each frame (all may be absent). */
    public record Inputs(VideoPipeline.Stats video, StreamHealth stream, boolean recording, long recordingMillis,
                         long recordingBytes, long freeDiskBytes, int recordingBitrateKbps,
                         MicrophoneProcessor.Stats microphone, NoiseCancellationManager.Status noise,
                         boolean microphoneCapturing) {
    }

    public static HealthReport build(Inputs in) {
        List<Metric> metrics = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        Condition network = Condition.OFFLINE;

        VideoPipeline.Stats video = in.video() == null ? VideoPipeline.Stats.EMPTY : in.video();
        metrics.add(new Metric("Video", "Rendering FPS", fps(video.renderFps()),
                "How fast Minecraft is drawing frames.", Severity.OK));
        if (video.canvas() != null) {
            metrics.add(new Metric("Video", "Program canvas", video.canvas().label(),
                    "The composition surface every output is taken from.", Severity.OK));
        }
        for (VideoPipeline.OutputStats output : video.outputs()) {
            Severity capture = output.captureFps() < output.targetFps() * 0.9 && output.captureFps() > 0
                    ? Severity.WARNING : Severity.OK;
            metrics.add(new Metric(output.name(), "Capture", String.format(Locale.ROOT, "%s at %s (target %d)",
                    output.resolution().label(), fps(output.captureFps()), output.targetFps()),
                    "Frames taken from the program canvas for this output (" + output.mode().displayName() + ").",
                    capture));
            metrics.add(new Metric(output.name(), "GPU readback", millis(output.readbackMillis()),
                    "Time copying each finished frame out of GPU memory.", output.readbackMillis() > 4 ? Severity.WARNING : Severity.OK));
            metrics.add(new Metric(output.name(), "Frames repeated (render lag)", Long.toString(output.renderRepeats()),
                    "Frames repeated because the game rendered slower than the output rate.",
                    output.renderRepeats() > output.targetFps() * 5L ? Severity.WARNING : Severity.OK));
            if (output.bufferExhausted() > 0 || output.readbackSkipped() > 0) {
                findings.add(new Finding(Severity.WARNING, output.name() + ": the capture pipeline fell behind "
                        + "(" + (output.bufferExhausted() + output.readbackSkipped()) + " captures delayed). "
                        + "The frame timeline was preserved with repeats."));
            }
            if (capture == Severity.WARNING && video.renderFps() > 0 && video.renderFps() < output.targetFps()) {
                findings.add(new Finding(Severity.WARNING, String.format(Locale.ROOT,
                        "Minecraft is rendering at %.0f FPS, below the %d FPS %s output; frames are being repeated.",
                        video.renderFps(), output.targetFps(), output.name().toLowerCase(Locale.ROOT))));
            }
        }
        if (video.frozen()) {
            findings.add(new Finding(Severity.INFO, "A Stream-able screen is open: outputs show the last game "
                    + "frame from before it opened."));
        }

        StreamHealth stream = in.stream() == null ? StreamHealth.OFFLINE : in.stream();
        if (stream.live()) {
            int target = stream.videoBitrateKbps() + stream.audioBitrateKbps();
            metrics.add(new Metric("Stream", "Encoder", stream.encoderName(), "The encoder in use.", Severity.OK));
            Severity encodeSeverity = stream.encodeFps() > 0 && stream.encodeFps() < stream.fps() * 0.95
                    ? Severity.CRITICAL : Severity.OK;
            metrics.add(new Metric("Stream", "Encoding FPS", fps(stream.encodeFps()) + " / " + stream.fps(),
                    "Frames FFmpeg is encoding per second.", encodeSeverity));
            metrics.add(new Metric("Stream", "Average encode time", millis(stream.encodeLatencyMillis()),
                    "From handing a frame to FFmpeg until it reports it encoded.",
                    stream.encodeLatencyMillis() > 150 ? Severity.WARNING : Severity.OK));
            metrics.add(new Metric("Stream", "Encoder utilisation", "Not reported",
                    "FFmpeg does not expose encoder utilisation for these encoders; see encode speed instead.",
                    Severity.INFO));
            metrics.add(new Metric("Stream", "Encode speed", stream.encodeSpeed() < 0 ? "-" :
                    String.format(Locale.ROOT, "%.2fx", stream.encodeSpeed()),
                    "1.00x means real time; lower means the stream is falling behind.",
                    stream.encodeSpeed() > 0 && stream.encodeSpeed() < 0.97 ? Severity.CRITICAL : Severity.OK));
            Severity bitrateSeverity = stream.outputKbps() > 0 && stream.outputKbps() < target * 0.85
                    ? Severity.WARNING : Severity.OK;
            metrics.add(new Metric("Stream", "Output bitrate", kbps(stream.outputKbps()) + " (target " + target + " kbps)",
                    "What FFmpeg is actually sending, all destinations combined per encoder.", bitrateSeverity));
            metrics.add(new Metric("Stream", "Audio bitrate", stream.audioBitrateKbps() + " kbps", "Program audio.", Severity.OK));
            Severity queue = stream.queuePressure() >= 0.8 ? Severity.CRITICAL
                    : stream.queuePressure() >= 0.4 ? Severity.WARNING : Severity.OK;
            metrics.add(new Metric("Stream", "Queue fill", Math.round(stream.queuePressure() * 100) + " %",
                    "Frames waiting for the encoder. Near 100 % means frames are about to be dropped.", queue));
            metrics.add(new Metric("Stream", "Dropped frames (encoding)", Long.toString(stream.framesDropped()),
                    "Pictures dropped because the encoder queue was full; their time was kept as repeats.",
                    stream.framesDropped() > 0 ? Severity.WARNING : Severity.OK));
            metrics.add(new Metric("Stream", "Reconnects", Integer.toString(stream.reconnects()),
                    "Automatic reconnections this session.", stream.reconnects() > 0 ? Severity.WARNING : Severity.OK));

            if (encodeSeverity == Severity.CRITICAL) {
                findings.add(new Finding(Severity.CRITICAL, String.format(Locale.ROOT,
                        "Encoder cannot maintain %d FPS (%.1f). Use a hardware encoder, a faster preset or a lower "
                                + "resolution.", stream.fps(), stream.encodeFps())));
            }
            boolean falling = stream.encodeSpeed() > 0 && stream.encodeSpeed() < 0.97;
            if (bitrateSeverity == Severity.WARNING && encodeSeverity == Severity.OK) {
                findings.add(new Finding(Severity.WARNING, "Upload bandwidth is below the configured bitrate: "
                        + kbps(stream.outputKbps()) + " of " + target + " kbps is getting out."));
            }
            if (queue != Severity.OK) {
                findings.add(new Finding(queue, "Network queue is repeatedly filling: the connection or encoder "
                        + "cannot keep up with " + target + " kbps."));
            }
            network = bitrateSeverity == Severity.WARNING || queue == Severity.CRITICAL || falling ? Condition.POOR
                    : queue == Severity.WARNING || stream.reconnects() > 0 ? Condition.FAIR : Condition.GOOD;
            if (stream.liveDestinationCount() == 0) {
                network = Condition.FAIR;
                findings.add(new Finding(Severity.INFO, "Connecting to destinations..."));
            }
        }

        if (in.recording()) {
            metrics.add(new Metric("Recording", "Duration", duration(in.recordingMillis()), "Time recorded.", Severity.OK));
            metrics.add(new Metric("Recording", "File size", bytes(in.recordingBytes()), "Video file so far.", Severity.OK));
            if (in.freeDiskBytes() > 0) {
                double bytesPerSecond = in.recordingMillis() > 5000 && in.recordingBytes() > 0
                        ? in.recordingBytes() / (in.recordingMillis() / 1000.0)
                        : in.recordingBitrateKbps() * 125.0;
                double secondsLeft = bytesPerSecond > 0 ? in.freeDiskBytes() / bytesPerSecond : -1;
                Severity disk = secondsLeft >= 0 && secondsLeft < 1800 ? Severity.CRITICAL
                        : secondsLeft >= 0 && secondsLeft < 7200 ? Severity.WARNING : Severity.OK;
                metrics.add(new Metric("Recording", "Disk time remaining", secondsLeft < 0 ? "-" : duration((long) (secondsLeft * 1000)),
                        "Free space divided by the recording's current data rate.", disk));
                if (disk != Severity.OK) {
                    findings.add(new Finding(disk, "Disk space may run out during this recording (about "
                            + duration((long) (secondsLeft * 1000)) + " left)."));
                }
            }
        }

        MicrophoneProcessor.Stats mic = in.microphone();
        if (mic != null && in.microphoneCapturing()) {
            metrics.add(new Metric("Microphone", "DSP latency", millis(mic.chainLatencyMillis()),
                    "Delay added by the processing chain (look-ahead and AI).", Severity.OK));
            metrics.add(new Metric("Microphone", "DSP time per 10 ms block", millis(mic.dspMillis()),
                    "CPU time processing each block; must stay well under 10 ms.",
                    mic.dspMillis() > 6 ? Severity.WARNING : Severity.OK));
            metrics.add(new Metric("Microphone", "Processing overruns", Long.toString(mic.overrunEvents()),
                    "Times the worker fell behind and skipped AI inference to keep latency fixed.",
                    mic.overrunEvents() > 0 ? Severity.WARNING : Severity.OK));
            metrics.add(new Metric("Microphone", "Dropped blocks", Long.toString(mic.droppedBlocks()),
                    "Audio discarded because processing was far behind.", mic.droppedBlocks() > 0 ? Severity.WARNING : Severity.OK));
            if (mic.overloaded() || mic.backlogBlocks() > 3) {
                findings.add(new Finding(Severity.WARNING, "Microphone processing is falling behind."));
            }
        }
        NoiseCancellationManager.Status noise = in.noise();
        if (noise != null && noise.state() == NoiseCancellationManager.Status.State.ACTIVE) {
            metrics.add(new Metric("Microphone", "AI model", noise.activeModel(), "Running locally.", Severity.OK));
            metrics.add(new Metric("Microphone", "AI inference", millis(noise.inferenceMillis())
                    + String.format(Locale.ROOT, " (RTF %.2f)", noise.realTimeFactor()),
                    "Inference time per hop; the real-time factor must stay below 1.",
                    noise.realTimeFactor() > 0.8 ? Severity.WARNING : Severity.OK));
            if (noise.realTimeFactor() > 0.8) {
                findings.add(new Finding(Severity.WARNING, "Noise cancellation cannot maintain real-time processing "
                        + "with the current backend; Stream-able will switch to a lighter model."));
            }
        } else if (noise != null && noise.state() == NoiseCancellationManager.Status.State.UNAVAILABLE) {
            findings.add(new Finding(Severity.INFO, noise.detail()));
        }

        if (findings.isEmpty() && (stream.live() || in.recording())) {
            findings.add(new Finding(Severity.OK, "Everything is running smoothly."));
        }
        return new HealthReport(List.copyOf(metrics), List.copyOf(findings), network);
    }

    static String fps(double value) {
        return value < 0 ? "-" : String.format(Locale.ROOT, "%.0f FPS", value);
    }

    static String millis(double value) {
        return value < 0 ? "-" : String.format(Locale.ROOT, "%.1f ms", value);
    }

    static String kbps(double value) {
        return value < 0 ? "-" : String.format(Locale.ROOT, "%,.0f kbps", value);
    }

    /** The most severe finding, or {@link Severity#OK}. */
    public Severity worst() {
        Severity worst = Severity.OK;
        for (Finding finding : findings) {
            if (finding.severity().ordinal() > worst.ordinal()) {
                worst = finding.severity();
            }
        }
        return worst;
    }

    public static String duration(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    static String bytes(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / (double) (1L << 30));
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1L << 20));
    }
}
