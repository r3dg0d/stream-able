package dev.streamable.diagnostics;

import dev.streamable.streaming.DestinationState;
import dev.streamable.streaming.StreamHealth;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthReportTest {

    private static StreamHealth live(double outKbps, double encodeFps, double speed, double queue, int reconnects) {
        return new StreamHealth(true, 60_000, 6000, 60, 3600, 0, queue, "NVIDIA NVENC H.264",
                List.of(new StreamHealth.DestinationStatus("Twitch", DestinationState.LIVE, "")),
                outKbps, encodeFps, 12, speed, 0, reconnects, 160, "1920x1080");
    }

    private static HealthReport report(StreamHealth stream, long freeDisk, boolean recording) {
        return HealthReport.build(new HealthReport.Inputs(null, stream, recording, 600_000, 1_500_000_000L,
                freeDisk, 40_000, null, null, false));
    }

    @Test
    void healthyStream() {
        HealthReport report = report(live(6150, 60, 1.0, 0.05, 0), -1, false);
        assertEquals(HealthReport.Condition.GOOD, report.network());
        assertEquals("Everything is running smoothly.", report.findings().getFirst().message());
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
}
