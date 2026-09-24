package dev.streamable.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsReportTest {

    @Test
    void configuredKeysNeverAppearAnywhere() {
        String key = "sk_custom_Q9zX2mP7";
        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put("Outputs", List.of(
                "Streaming: IDLE - last error: rtmp://ingest.example.com/live/" + key + ": Connection refused",
                "  Weird device name containing " + key));
        sections.put("Environment", List.of("Java: 25"));

        String report = DiagnosticsReport.build(new DiagnosticsReport.Input(sections, List.of(key)));

        assertFalse(report.contains(key));
        assertFalse(report.contains("Q9zX2mP7"));
        assertTrue(report.contains("## Outputs"));
        assertTrue(report.contains("Java: 25"));
    }

    @Test
    void wellKnownKeyShapesAreRemovedEvenWhenNotConfigured() {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put("Log", List.of("publish failed for live_123456789_AbCdEfGhIjKlMnOp"));

        String report = DiagnosticsReport.build(new DiagnosticsReport.Input(sections, List.of()));

        assertFalse(report.contains("live_123456789_AbCdEfGhIjKlMnOp"));
    }
}
