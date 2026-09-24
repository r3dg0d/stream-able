package dev.streamable.ui.studio;

import dev.streamable.StreamAbleClient;
import dev.streamable.diagnostics.HealthReport;
import dev.streamable.streaming.StreamHealth;
import dev.streamable.ui.kit.Layouts;
import dev.streamable.ui.kit.Painter;
import dev.streamable.ui.kit.Theme;
import dev.streamable.ui.kit.Toggle;
import dev.streamable.ui.kit.UiNode;
import dev.streamable.ui.kit.Widgets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stream Health: what is happening across the pipeline, in plain language,
 * with what to do about it. Rebuilt from live numbers twice a second.
 */
final class HealthPage {

    private HealthPage() {
    }

    static void build(Studio s, Layouts.Column page) {
        StreamAbleClient client = s.client();

        Layouts.Grid top = page.add(new Layouts.Grid(110, Theme.SPACE_4));
        top.add(new Widgets.MetricCard("Stream", () -> client.streaming().isLive()
                ? "LIVE " + client.health().formattedUptime() : "Offline",
                () -> client.streaming().isLive() ? Theme.LIVE : Theme.TEXT_MUTED,
                () -> client.streaming().isLive() ? client.health().liveDestinationCount() + " destination(s) live" : null));
        top.add(new Widgets.MetricCard("Network", () -> client.healthReport().network().label(),
                () -> Studio.conditionColor(client.healthReport().network()),
                () -> client.streaming().isLive() ? "from delivered bitrate and encoder queue" : "while live"));
        top.add(new Widgets.MetricCard("Recording", () -> client.recording().isActive()
                ? Studio.clock(client.recording().elapsedMillis()) : "Off",
                () -> client.recording().isActive() ? Theme.RECORDING : Theme.TEXT_MUTED,
                () -> client.recording().isActive() ? Studio.bytes(client.recording().currentFileSizeBytes()) : null));
        top.add(new Widgets.MetricCard("Game", () -> net.minecraft.client.Minecraft.getInstance().getFps() + " FPS",
                () -> Theme.TEXT, () -> "rendering"));

        Widgets.Card findings = page.add(new Widgets.Card(Theme.SPACE_4));
        findings.add(new Widgets.Caption("What needs attention"));
        findings.add(new Findings(client));

        Widgets.Card details = page.add(new Widgets.Card(Theme.SPACE_4));
        details.add(new Widgets.Caption("Details"));
        details.add(new MetricTable(client));

        Widgets.Card destinations = page.add(new Widgets.Card(Theme.SPACE_4));
        destinations.visibleWhen(() -> client.streaming().isLive());
        destinations.add(new Widgets.Caption("Destinations"));
        destinations.add(new DestinationTable(client));

        Widgets.Card hud = page.add(new Widgets.Card(Theme.SPACE_4));
        hud.add(new Widgets.Caption("In-game overlay"));
        hud.add(Toggle.of("Show the stream HUD while live", () -> client.config().ui.showStreamHud, v -> {
            client.config().ui.showStreamHud = v;
            s.changed();
        }).detail(() -> "Compact and only on your screen. Drag it in the canvas editor (F7); toggle with F8."));
        hud.add(Toggle.of("Detailed HUD", () -> client.config().ui.detailedStreamHud, v -> {
            client.config().ui.detailedStreamHud = v;
            s.changed();
        }).detail(() -> "Adds encoder, dropped frames and the microphone meter."));
    }

    /** Findings sorted by severity, each in its colour; a clear message when all is well. */
    private static final class Findings extends UiNode {
        private final StreamAbleClient client;

        Findings(StreamAbleClient client) {
            this.client = client;
        }

        private List<HealthReport.Finding> sorted() {
            List<HealthReport.Finding> list = new ArrayList<>(client.healthReport().findings());
            list.sort((a, b) -> b.severity().ordinal() - a.severity().ordinal());
            return list;
        }

        @Override
        public int preferredHeight(int availableWidth) {
            var screen = screen();
            List<HealthReport.Finding> list = sorted();
            if (list.isEmpty() || screen == null) {
                return 12;
            }
            int h = 0;
            for (HealthReport.Finding f : list) {
                h += Math.max(12, screen.measureParagraph(f.message(), availableWidth - 14, 1f)) + 4;
            }
            return h;
        }

        @Override
        protected void renderSelf(Painter p) {
            List<HealthReport.Finding> list = sorted();
            if (list.isEmpty()) {
                p.circle(x + 4, y + 5, 2.5f, Theme.SUCCESS);
                p.text("Everything looks healthy.", x + 12, y + 1, Theme.TEXT_SECONDARY);
                return;
            }
            int cy = y;
            for (HealthReport.Finding f : list) {
                p.circle(x + 4, cy + 5, 2.5f, Studio.severityColor(f.severity()));
                int used = p.paragraph(f.message(), x + 12, cy + 1, width - 14, Theme.TEXT, 1f);
                cy += Math.max(12, used) + 4;
            }
        }
    }

    /** Metrics grouped by area; two columns on wide windows. Explanations under each value. */
    private static final class MetricTable extends UiNode {
        private static final int ROW = 22;
        private final StreamAbleClient client;

        MetricTable(StreamAbleClient client) {
            this.client = client;
        }

        private Map<String, List<HealthReport.Metric>> groups() {
            Map<String, List<HealthReport.Metric>> groups = new LinkedHashMap<>();
            for (HealthReport.Metric m : client.healthReport().metrics()) {
                groups.computeIfAbsent(m.group(), k -> new ArrayList<>()).add(m);
            }
            return groups;
        }

        private int columns(int w) {
            return w >= 520 ? 2 : 1;
        }

        @Override
        public int preferredHeight(int availableWidth) {
            int cols = columns(availableWidth);
            int[] heights = new int[cols];
            for (List<HealthReport.Metric> group : groups().values()) {
                heights[shortest(heights)] += 14 + group.size() * ROW + 8;
            }
            int max = 0;
            for (int h : heights) {
                max = Math.max(max, h);
            }
            return Math.max(12, max);
        }

        private static int shortest(int[] heights) {
            int best = 0;
            for (int c = 1; c < heights.length; c++) {
                if (heights[c] < heights[best]) {
                    best = c;
                }
            }
            return best;
        }

        @Override
        protected void renderSelf(Painter p) {
            int cols = columns(width);
            int colW = (width - (cols - 1) * Theme.SPACE_6) / cols;
            int[] heights = new int[cols];
            for (Map.Entry<String, List<HealthReport.Metric>> group : groups().entrySet()) {
                int column = shortest(heights);
                int gx = x + column * (colW + Theme.SPACE_6);
                int gy = y + heights[column];
                p.text(group.getKey().toUpperCase(java.util.Locale.ROOT), gx, gy + 1, Theme.TEXT_MUTED,
                        Theme.TEXT_CAPTION, Painter.Weight.SEMIBOLD);
                int ry = gy + 14;
                for (HealthReport.Metric m : group.getValue()) {
                    int color = Studio.severityColor(m.severity());
                    p.circle(gx + 3, ry + 4.5f, 2f, m.severity() == HealthReport.Severity.OK ? Theme.TEXT_DISABLED : color);
                    int vw = p.textWidth(m.value(), 1f, Painter.Weight.SEMIBOLD);
                    p.textClipped(m.name(), gx + 10, ry, colW - vw - 16, Theme.TEXT, 1f, Painter.Weight.REGULAR);
                    p.text(m.value(), gx + colW - vw, ry, m.severity() == HealthReport.Severity.OK ? Theme.TEXT : color,
                            1f, Painter.Weight.SEMIBOLD);
                    p.textClipped(m.explanation(), gx + 10, ry + 10, colW - 10, Theme.TEXT_MUTED, 0.75f,
                            Painter.Weight.REGULAR);
                    ry += ROW;
                }
                heights[column] += 14 + group.getValue().size() * ROW + 8;
            }
        }
    }

    private static final class DestinationTable extends UiNode {
        private final StreamAbleClient client;

        DestinationTable(StreamAbleClient client) {
            this.client = client;
        }

        @Override
        public int preferredHeight(int availableWidth) {
            return Math.max(12, client.health().destinations().size() * 14);
        }

        @Override
        protected void renderSelf(Painter p) {
            int cy = y;
            for (StreamHealth.DestinationStatus d : client.health().destinations()) {
                p.circle(x + 4, cy + 4.5f, 2.5f, d.state().colour() | 0xFF000000);
                p.textClipped(d.name(), x + 12, cy, width / 3, Theme.TEXT, 1f, Painter.Weight.REGULAR);
                p.text(d.state().displayName(), x + width / 3 + 16, cy, Theme.TEXT_SECONDARY);
                p.textClipped(d.detail(), x + width / 2 + 16, cy, width / 2 - 16, Theme.TEXT_MUTED, 1f,
                        Painter.Weight.REGULAR);
                cy += 14;
            }
        }
    }
}
