package dev.streamable.diagnostics;

import dev.streamable.pipeline.VideoPipeline;
import dev.streamable.streaming.DestinationState;
import dev.streamable.streaming.StreamHealth;
import dev.streamable.video.Resolution;
import dev.streamable.video.ScalingMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthReportTest {

    @Test
    void destinationOverflowRemainsVisibleWithHealthyEncoder() {
        StreamHealth h = live(-1, 60, 1, 0, 0);
        StreamHealth congested = new StreamHealth(h.live(), h.uptimeMillis(), h.videoBitrateKbps(),
                h.fps(), h.framesSubmitted(), h.framesDropped(), h.queuePressure(), h.encoderName(),
                h.destinations(), h.outputKbps(), h.encodeFps(), h.encodeLatencyMillis(), h.encodeSpeed(),
                h.framesRepeatedForTiming(), h.reconnects(), h.audioBitrateKbps(), h.outputResolution(), 2);
        HealthReport r = report(congested, Long.MAX_VALUE, false);
        assertEquals(HealthReport.Condition.POOR, r.network());
        assertTrue(r.findings().stream().anyMatch(f -> f.message().startsWith("A destination queue overflowed")));
    }

    private static StreamHealth live(double outKbps, double encodeFps, double speed, double queue, int reconnects) {
        return live(outKbps, encodeFps, speed, queue, reconnects,
                List.of(new StreamHealth.DestinationStatus("Twitch", DestinationState.LIVE, "")));
    }

    private static StreamHealth live(double outKbps, double encodeFps, double speed, double queue, int reconnects,
                                     List<StreamHealth.DestinationStatus> destinations) {
        return new StreamHealth(true, 60_000, 6000, 60, 3600, 0, queue, "NVIDIA NVENC H.264",
                destinations, outKbps, encodeFps, 12, speed, 0, reconnects, 160, "1920x1080");
    }

    private static HealthReport report(StreamHealth stream, long freeDisk, boolean recording) {
        return HealthReport.build(new HealthReport.Inputs(null, stream, recording, 600_000, 1_500_000_000L,
                freeDisk, 40_000, null, null, false));
    }


    private static VideoPipeline.OutputStats output(String name, boolean broken, long skipped, long exhausted) {
        return new VideoPipeline.OutputStats(name, Resolution.FULL_HD, ScalingMode.FIT, 60,
                broken ? 0 : 60, broken ? -1 : 1.2, 0, 0, skipped, exhausted, 0, broken);
    }

    private static HealthReport reportWithVideo(VideoPipeline.Stats video, StreamHealth stream, boolean recording) {
        return HealthReport.build(new HealthReport.Inputs(video, stream, recording, 600_000, 1_500_000_000L,
                -1, 40_000, null, null, false));
    }

    @Test
    void anExtraFindingComesFirstAndSetsTheWorstSeverity() {
        HealthReport base = report(live(6150, 60, 1.0, 0.05, 0), -1, false);
        HealthReport with = base.withFinding(HealthReport.Severity.CRITICAL, "Minecraft is not rendering with OpenGL");
        assertEquals("Minecraft is not rendering with OpenGL", with.findings().getFirst().message());
        assertEquals(base.findings().size() + 1, with.findings().size());
        assertEquals(HealthReport.Severity.CRITICAL, with.worst());
        assertEquals(base.metrics(), with.metrics());
        assertEquals(base.network(), with.network());
        // the original is untouched
        assertFalse(base.findings().stream().anyMatch(f -> f.message().contains("OpenGL")));
    }

    @Test
    void healthyStream() {
        HealthReport report = report(live(6150, 60, 1.0, 0.05, 0), -1, false);
        assertEquals(HealthReport.Condition.GOOD, report.network());
        assertEquals("Everything is running smoothly.", report.findings().getFirst().message());
        assertTrue(report.metrics().stream().anyMatch(m -> m.name().equals("Destinations")
                && m.value().startsWith("1 live / 1")));
    }

    @Test
    void slowEncoderIsNamed() {
        HealthReport report = report(live(6000, 41, 0.7, 0.9, 0), -1, false);
        assertTrue(report.findings().stream().anyMatch(f -> f.message().startsWith("Output cannot maintain 60 FPS")));
        assertEquals(HealthReport.Condition.POOR, report.network());
    }

    @Test
    void lowUploadIsNamed() {
        HealthReport report = report(live(3000, 60, 1.0, 0.6, 1), -1, false);
        assertTrue(report.findings().stream().anyMatch(f -> f.message().startsWith("Encoded output is below")));
        assertTrue(report.findings().stream().anyMatch(f -> f.message().startsWith("Encoder input queue is filling")));
    }

    @Test
    void diskSpaceWarning() {
        // 1.5 GB in 10 minutes = 2.5 MB/s; 2 GB free = ~13 minutes left.
        HealthReport report = report(StreamHealth.OFFLINE, 2_000_000_000L, true);
        assertTrue(report.findings().stream().anyMatch(f -> f.message().startsWith("Disk space may run out")));
    }

    @Test
    void unmeasurableFiguresAreNotInvented() {
        HealthReport report = report(live(6150, 60, 1.0, 0.05, 0), -1, false);
        assertTrue(report.metrics().stream().anyMatch(m -> m.name().equals("Encoder utilisation")
                && m.value().equals("Not reported")));
    }

    @Test
    void partialTeeSlaveFailureIsNamedAndNotSmooth() {
        StreamHealth stream = live(6150, 60, 1.0, 0.05, 0, List.of(
                new StreamHealth.DestinationStatus("Twitch", DestinationState.LIVE, ""),
                new StreamHealth.DestinationStatus("YouTube", DestinationState.ERROR,
                        "Error opening output rtmps://a.rtmps.youtube.com/live2/<REDACTED>")));
        HealthReport report = report(stream, -1, false);
        assertEquals(HealthReport.Condition.POOR, report.network());
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == HealthReport.Severity.CRITICAL
                        && f.message().startsWith("YouTube dropped:")));
        assertFalse(report.findings().stream().anyMatch(f ->
                f.message().equals("Everything is running smoothly.")));
        assertTrue(report.metrics().stream().anyMatch(m -> m.name().equals("Destinations")
                && m.value().contains("1 live / 2")
                && m.value().contains("1 failed")
                && m.severity() == HealthReport.Severity.CRITICAL));
    }

    @Test
    void reconnectingDestinationIsWarned() {
        StreamHealth stream = live(6150, 60, 1.0, 0.05, 1, List.of(
                new StreamHealth.DestinationStatus("Twitch", DestinationState.RECONNECTING,
                        "Connection lost. Retrying in 5 seconds...")));
        HealthReport report = report(stream, -1, false);
        assertEquals(HealthReport.Condition.FAIR, report.network());
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == HealthReport.Severity.WARNING
                        && f.message().startsWith("Twitch:")));
    }

    @Test
    void allDestinationsFailedWithoutLiveIsPoor() {
        StreamHealth stream = live(0, 60, 1.0, 0.0, 2, List.of(
                new StreamHealth.DestinationStatus("Twitch", DestinationState.ERROR, "Could not start the broadcast."),
                new StreamHealth.DestinationStatus("YouTube", DestinationState.ERROR, "Could not start the broadcast.")));
        HealthReport report = report(stream, -1, false);
        assertEquals(HealthReport.Condition.POOR, report.network());
        assertEquals(2, report.findings().stream()
                .filter(f -> f.severity() == HealthReport.Severity.CRITICAL).count());
        assertFalse(report.findings().stream().anyMatch(f ->
                f.message().equals("Connecting to destinations...")));
    }


    private static HealthReport reportWithMic(dev.streamable.audio.mic.MicrophoneProcessor.Stats mic,
                                              boolean capturing) {
        return HealthReport.build(new HealthReport.Inputs(null, live(6150, 60, 1.0, 0.05, 0), false,
                60_000, 0, -1, 40_000, mic, null, capturing));
    }

    @Test
    void micOverloadFindingIsActionable() {
        var mic = new dev.streamable.audio.mic.MicrophoneProcessor.Stats(
                7.5, 9.0, 40.0, 8, 12, 3, 0, 500, true);
        HealthReport report = reportWithMic(mic, true);
        assertTrue(report.metrics().stream().anyMatch(m ->
                m.group().equals("Microphone") && m.name().equals("Queue backlog")
                        && m.value().equals("8 blocks")
                        && m.severity() == HealthReport.Severity.WARNING));
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == HealthReport.Severity.CRITICAL
                        && f.message().startsWith("Microphone DSP is overloaded")
                        && f.message().contains("8 blocks backlog")
                        && f.message().contains("3 overruns")
                        && f.message().contains("12 blocks dropped")
                        && f.message().contains("Studio → Audio")
                        && f.message().contains("lighter noise model")));
        assertFalse(report.findings().stream().anyMatch(f ->
                f.message().equals("Everything is running smoothly.")));
        assertFalse(report.findings().stream().anyMatch(f ->
                f.message().equals("Microphone processing is falling behind.")));
    }

    @Test
    void micBacklogWithoutDropsIsWarningAndHealthyMicIsQuiet() {
        var pressured = new dev.streamable.audio.mic.MicrophoneProcessor.Stats(
                4.0, 5.0, 40.0, 5, 0, 1, 0, 200, false);
        HealthReport pressuredReport = reportWithMic(pressured, true);
        assertTrue(pressuredReport.findings().stream().anyMatch(f ->
                f.severity() == HealthReport.Severity.WARNING
                        && f.message().startsWith("Microphone DSP is overloaded")
                        && f.message().contains("5 blocks backlog")
                        && !f.message().contains("dropped")));

        var healthy = new dev.streamable.audio.mic.MicrophoneProcessor.Stats(
                2.0, 3.0, 40.0, 0, 0, 0, 0, 1000, false);
        HealthReport healthyReport = reportWithMic(healthy, true);
        assertTrue(healthyReport.metrics().stream().anyMatch(m ->
                m.group().equals("Microphone") && m.name().equals("Queue backlog")
                        && m.value().equals("0 blocks")
                        && m.severity() == HealthReport.Severity.OK));
        assertFalse(healthyReport.findings().stream().anyMatch(f ->
                f.message().startsWith("Microphone DSP is overloaded")));
        assertTrue(healthyReport.findings().stream().anyMatch(f ->
                f.message().equals("Everything is running smoothly.")));
    }

    @Test
    void brokenOutputIsCriticalAndNotSmooth() {
        VideoPipeline.Stats video = new VideoPipeline.Stats(Resolution.FULL_HD, 60, 0.5,
                output("Recording", true, 0, 0), null, null, false);
        HealthReport report = reportWithVideo(video, live(6150, 60, 1.0, 0.05, 0), true);
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == HealthReport.Severity.CRITICAL
                        && f.message().startsWith("Recording: GPU capture failed")));
        assertFalse(report.findings().stream().anyMatch(f ->
                f.message().equals("Everything is running smoothly.")));
        assertTrue(report.metrics().stream().anyMatch(m ->
                m.group().equals("Recording") && m.name().equals("Capture")
                        && m.value().equals("Broken")
                        && m.severity() == HealthReport.Severity.CRITICAL));
        assertEquals(HealthReport.Severity.CRITICAL, report.worst());
    }

    @Test
    void readbackPressureAddsMetricsAndFinding() {
        VideoPipeline.Stats video = new VideoPipeline.Stats(Resolution.FULL_HD, 60, 0.5,
                null, output("Streaming", false, 4, 2), null, false);
        HealthReport report = reportWithVideo(video, live(6150, 60, 1.0, 0.05, 0), false);
        assertTrue(report.metrics().stream().anyMatch(m ->
                m.group().equals("Streaming") && m.name().equals("Readback skips")
                        && m.value().equals("4")
                        && m.severity() == HealthReport.Severity.WARNING));
        assertTrue(report.metrics().stream().anyMatch(m ->
                m.group().equals("Streaming") && m.name().equals("Buffers exhausted")
                        && m.value().equals("2")));
        assertTrue(report.findings().stream().anyMatch(f ->
                f.severity() == HealthReport.Severity.WARNING
                        && f.message().contains("captures delayed")
                        && f.message().contains("6")));
        assertFalse(report.findings().stream().anyMatch(f ->
                f.message().equals("Everything is running smoothly.")));
    }

}
