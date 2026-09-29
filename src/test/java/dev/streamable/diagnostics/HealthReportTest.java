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
        assertTrue(report.findings().stream().anyMatch(f -> f.message().startsWith("Encoder cannot maintain 60 FPS")));
        assertEquals(HealthReport.Condition.POOR, report.network());
    }

    @Test
    void lowUploadIsNamed() {
        HealthReport report = report(live(3000, 60, 1.0, 0.6, 1), -1, false);
        assertTrue(report.findings().stream().anyMatch(f -> f.message().startsWith("Upload bandwidth is below")));
        assertTrue(report.findings().stream().anyMatch(f -> f.message().startsWith("Network queue is repeatedly filling")));
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
