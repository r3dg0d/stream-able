package dev.streamable.ui.studio;

import dev.streamable.StreamAbleClient;
import dev.streamable.streaming.StreamDestination;
import dev.streamable.streaming.StreamPlatform;
import dev.streamable.streaming.StreamingCredentials;
import dev.streamable.streaming.test.NetworkProbe;
import dev.streamable.streaming.test.StreamTestMetrics;
import dev.streamable.streaming.test.StreamTestPlan;
import dev.streamable.streaming.test.StreamTestSession;
import dev.streamable.ui.kit.Button;
import dev.streamable.ui.kit.IconButton;
import dev.streamable.ui.kit.Icons;
import dev.streamable.ui.kit.Label;
import dev.streamable.ui.kit.Layouts;
import dev.streamable.ui.kit.Painter;
import dev.streamable.ui.kit.Segmented;
import dev.streamable.ui.kit.TextField;
import dev.streamable.ui.kit.Theme;
import dev.streamable.ui.kit.Toggle;
import dev.streamable.ui.kit.UiNode;
import dev.streamable.ui.kit.Widgets;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Destinations: where the stream goes, their credentials, and the safe
 * destination test.
 *
 * <p>Stream keys are credentials. The key field is masked by default, never
 * copied out while masked, and nothing on this page (status, publish URL,
 * test output, diagnostics) ever shows it.</p>
 */
final class DestinationsPage {

    private static final int[] TEST_SECONDS = {10, 30, 60};
    private static int testSecondsIndex = 1;

    private DestinationsPage() {
    }

    static void build(Studio s, Layouts.Column page) {
        StreamAbleClient client = s.client();
        List<StreamDestination> destinations = client.streaming().destinations();
        StreamDestination selected = find(destinations, s.selectedDestination);
        if (selected == null && !destinations.isEmpty()) {
            selected = destinations.getFirst();
            s.selectedDestination = selected.id();
        }

        Widgets.Card list = page.add(new Widgets.Card(Theme.SPACE_2));
        list.add(new Widgets.Caption("Destinations - every enabled one goes live together"));
        if (destinations.isEmpty()) {
            list.add(new Label(() -> "Add where you stream to. You can stream to several services at once.")
                    .color(Theme.TEXT_MUTED).wrap());
        }
        for (StreamDestination destination : destinations) {
            list.add(new DestinationRow(s, destination));
        }
        Layouts.Grid add = list.add(new Layouts.Grid(96, Theme.SPACE_3));
        for (StreamPlatform platform : StreamPlatform.values()) {
            add.add(Button.of(platform.displayName(), () -> {
                List<StreamDestination> updated = new ArrayList<>(client.streaming().destinations());
                StreamDestination created = StreamDestination.create(platform);
                updated.add(created);
                client.streaming().setDestinations(updated);
                s.selectedDestination = created.id();
                s.changed();
                s.screen().refresh();
            }).icon(Icons.Icon.PLUS).variant(Button.Variant.GHOST).enabledWhen(() -> !client.streaming().isLive()));
        }

        if (selected != null) {
            editor(s, page.add(new Widgets.Card(Theme.SPACE_5)), selected);
            testCard(s, page.add(new Widgets.Card(Theme.SPACE_5)), selected);
        }
    }

    private static StreamDestination find(List<StreamDestination> list, UUID id) {
        for (StreamDestination d : list) {
            if (d.id().equals(id)) {
                return d;
            }
        }
        return null;
    }

    private static final class DestinationRow extends UiNode {
        private final Studio studio;
        private final StreamDestination destination;

        DestinationRow(Studio s, StreamDestination destination) {
            this.studio = s;
            this.destination = destination;
            add(new Toggle(() -> "", destination::enabled, v -> {
                destination.setEnabled(v);
                s.changed();
            })).tooltip("Include when going live.");
        }

        @Override
        public boolean isFocusable() {
            return true;
        }

        @Override
        public int preferredHeight(int availableWidth) {
            return 20;
        }

        @Override
        protected void layout() {
            children.getFirst().setBounds(x + width - 34, y + 2, 30, Theme.CONTROL_HEIGHT);
        }

        @Override
        protected void renderSelf(Painter p) {
            boolean selected = destination.id().equals(studio.selectedDestination);
            p.roundRect(x, y, width, height, Theme.RADIUS, selected ? Theme.ACCENT_SOFT
                    : Theme.withAlpha(Theme.SURFACE_HOVER, hover.eased()));
            p.circle(x + 8, y + height / 2f, 2.5f, destination.state().colour() | 0xFF000000);
            String problem = destination.validate();
            String status = !destination.enabled() ? "Off" : problem != null ? "Needs setup"
                    : destination.state().displayName();
            int sw = p.textWidth(status, Theme.TEXT_CAPTION, Painter.Weight.REGULAR);
            String title = destination.name().equals(destination.platform().displayName()) ? destination.name()
                    : destination.name() + "  ·  " + destination.platform().displayName();
            p.textClipped(title, x + 16, y + 6,
                    width - 60 - sw, Theme.TEXT, 1f, selected ? Painter.Weight.SEMIBOLD : Painter.Weight.REGULAR);
            p.text(status, x + width - 42 - sw, y + 7, problem != null && destination.enabled() ? Theme.WARNING
                    : Theme.TEXT_MUTED, Theme.TEXT_CAPTION, Painter.Weight.REGULAR);
        }

        @Override
        public boolean mouseDown(double mx, double my, int button) {
            if (button == 0) {
                return activate();
            }
            return false;
        }

        @Override
        public boolean activate() {
            studio.selectedDestination = destination.id();
            studio.screen().refresh();
            return true;
        }
    }

    // ---- editor --------------------------------------------------------------------------

    private static void editor(Studio s, Widgets.Card card, StreamDestination d) {
        StreamAbleClient client = s.client();
        Layouts.Row head = card.add(new Layouts.Row(Theme.SPACE_3));
        head.add(new Widgets.SectionHeader(d.name(), () -> d.platform().advice()), -1);
        head.add(Button.of("Remove", () -> s.screen().confirm("Remove " + d.name() + "?",
                "Its URL and stream key are deleted from this computer.", "Remove", true, () -> {
                    List<StreamDestination> updated = new ArrayList<>(client.streaming().destinations());
                    updated.removeIf(x -> x.id().equals(d.id()));
                    client.streaming().setDestinations(updated);
                    s.selectedDestination = null;
                    s.changed();
                    s.screen().refresh();
                })).variant(Button.Variant.DANGER).enabledWhen(() -> !client.streaming().isLive()), 66);

        Layouts.Grid grid = card.add(new Layouts.Grid(200, Theme.SPACE_5));
        grid.add(new TextField("Name", d::name, v -> {
            d.setName(v);
            s.changed();
        }).maxLength(48));
        grid.add(s.enumDropdown("Service", StreamPlatform.values(), StreamPlatform::displayName, d::platform, v -> {
            String url = d.credentials().ingestUrl();
            if (url.isBlank() || url.equals(d.platform().defaultIngestUrl())) {
                d.setCredentials(new StreamingCredentials(v.defaultIngestUrl(), d.credentials().streamKey()));
            }
            d.setPlatform(v);
        }));
        card.add(new TextField("Server URL", () -> d.credentials().ingestUrl(), v -> {
            d.setCredentials(new StreamingCredentials(v.strip(), d.credentials().streamKey()));
            s.changed();
        }).maxLength(512).placeholder("rtmp://... or rtmps://...").commitOnBlur());

        TextField key = new TextField("Stream key", () -> d.credentials().streamKey(), v -> {
            d.setCredentials(new StreamingCredentials(d.credentials().ingestUrl(), v.strip()));
            s.changed();
        }).secret().maxLength(512).placeholder("Paste from your service's dashboard");
        Layouts.Row keyRow = card.add(new Layouts.Row(Theme.SPACE_3));
        keyRow.add(key, -1);
        Layouts.Column keyActions = keyRow.add(new Layouts.Column(0), 76);
        keyActions.add(Layouts.spacer(11));
        Layouts.Row buttons = keyActions.add(new Layouts.Row(Theme.SPACE_2));
        buttons.add(new IconButton(() -> key.isRevealed() ? Icons.Icon.EYE_OFF : Icons.Icon.EYE,
                "Show or hide the key. Hide it before showing your screen.", key::toggleRevealed), 16);
        buttons.add(IconButton.of(Icons.Icon.COPY, "Paste the key from the clipboard", () -> {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard().strip();
            d.setCredentials(new StreamingCredentials(d.credentials().ingestUrl(), clip));
            s.changed();
            s.toast("Stream key pasted.");
        }), 16);
        buttons.add(IconButton.of(Icons.Icon.CLOSE, "Clear the key", () -> {
            d.setCredentials(new StreamingCredentials(d.credentials().ingestUrl(), ""));
            s.changed();
        }), 16);
        card.add(new Label(() -> "Stream keys stay on this computer in Stream-able's config and are removed from "
                + "logs, diagnostics and error messages.").color(Theme.TEXT_MUTED).scale(Theme.TEXT_CAPTION).wrap());

        card.add(new Widgets.Notice(d::validate, () -> Theme.WARNING));
        card.add(new Label(() -> {
            String url = d.credentials().redactedPublishUrl();
            return url.isEmpty() ? "" : "Publishing to " + url;
        }).color(Theme.TEXT_SECONDARY).scale(Theme.TEXT_CAPTION));
        card.add(new Widgets.Notice(() -> d.lastError() == null || d.lastError().isBlank() ? null
                : "Last error: " + d.lastError(), () -> Theme.DANGER));

        Layouts.Row live = card.add(new Layouts.Row(Theme.SPACE_3));
        live.visibleWhen(() -> client.streaming().isLive());
        live.add(Button.of("Reconnect", () -> {
            boolean ok = client.streaming().reconnectDestination(d.id());
            if (ok) {
                s.toast("Reconnecting " + d.name() + ".");
            } else {
                s.error("Could not reconnect " + d.name() + ".");
            }
        }).icon(Icons.Icon.REFRESH), 90);
        live.add(Button.of("Copy diagnostics", () -> s.copyToClipboard(client.streaming().diagnosticsFor(d.id()),
                "Diagnostics copied (stream key removed).")).icon(Icons.Icon.COPY).variant(Button.Variant.GHOST), 120);
        live.add(Layouts.spacer(0), -1);
    }

    // ---- test -------------------------------------------------------------------------------

    private static StreamTestSession sessionFor(StreamAbleClient client, StreamDestination d) {
        StreamTestSession session = client.streamTests().session();
        return session != null && session.destination().id().equals(d.id()) ? session : null;
    }

    private static void testCard(Studio s, Widgets.Card card, StreamDestination d) {
        StreamAbleClient client = s.client();
        StreamTestPlan plan = StreamTestPlan.forDestination(d);
        card.add(new Widgets.SectionHeader("Test this destination", () ->
                plan.mode() == StreamTestPlan.Mode.SERVICE_BANDWIDTH_TEST
                        ? "Service test mode - your channel does not go live"
                        : "Local encoder test + connectivity check - nothing is published"));
        card.add(new Label(plan::explanation).color(Theme.TEXT_SECONDARY).scale(Theme.TEXT_CAPTION).wrap());

        Layouts.Row controls = card.add(new Layouts.Row(Theme.SPACE_3));
        controls.add(new Segmented(List.of("10 s", "30 s", "60 s"), () -> testSecondsIndex, i -> testSecondsIndex = i)
                .enabledWhen(() -> !client.streamTests().isRunning()), 120);
        controls.add(new Button(() -> client.streamTests().isRunning() ? "Cancel test" : "Run test", () -> {
            if (client.streamTests().isRunning()) {
                client.streamTests().cancel();
                return;
            }
            String problem = d.validate();
            if (problem != null && plan.mode() == StreamTestPlan.Mode.SERVICE_BANDWIDTH_TEST) {
                s.error(problem);
                return;
            }
            String error = client.startDestinationTest(d, TEST_SECONDS[testSecondsIndex]);
            if (error != null) {
                s.error(error);
            }
        }).icon(() -> client.streamTests().isRunning() ? Icons.Icon.STOP : Icons.Icon.TEST)
                .variant(() -> client.streamTests().isRunning() ? Button.Variant.DANGER : Button.Variant.PRIMARY)
                .enabledWhen(() -> !client.streaming().isLive()), 96);
        controls.add(new Widgets.StatusPill(() -> {
            StreamTestSession session = sessionFor(client, d);
            return session == null ? "Not run" : stateLabel(session.state());
        }, () -> {
            StreamTestSession session = sessionFor(client, d);
            return session == null ? Theme.TEXT_MUTED : stateColor(session.state());
        }, true), -1);
        card.add(new Widgets.Notice(() -> client.streaming().isLive()
                ? "Tests are unavailable while live: they would compete with the stream for the encoder and uplink."
                : null, () -> Theme.INFO));

        card.add(new Widgets.ProgressBar(() -> {
            StreamTestSession session = sessionFor(client, d);
            if (session == null) {
                return 0;
            }
            StreamTestSession.State state = session.state();
            if (state == StreamTestSession.State.PREPARING || state == StreamTestSession.State.CONNECTING) {
                return -1;
            }
            return session.metrics().elapsedSeconds() / TEST_SECONDS[testSecondsIndex];
        }, () -> Theme.ACCENT)).visibleWhen(() -> {
            StreamTestSession session = sessionFor(client, d);
            return session != null && session.isRunning();
        });
        card.add(new Label(() -> {
            StreamTestSession session = sessionFor(client, d);
            return session == null ? "" : session.message();
        }).color(Theme.TEXT_SECONDARY).wrap()).visibleWhen(() -> sessionFor(client, d) != null);

        Layouts.Grid metrics = card.add(new Layouts.Grid(96, Theme.SPACE_4));
        metrics.visibleWhen(() -> sessionFor(client, d) != null);
        boolean upload = plan.mode() == StreamTestPlan.Mode.SERVICE_BANDWIDTH_TEST;
        metrics.add(new Widgets.MetricCard(upload ? "Upload now" : "Bitrate now", () -> {
            StreamTestSession session = sessionFor(client, d);
            return session != null && session.isRunning() ? kbps(metricsOf(client, d).achievedKbps()) : "-";
        }, () -> upload ? uploadColor(metricsOf(client, d).achievedKbps(), metricsOf(client, d).targetKbps()) : Theme.TEXT,
                () -> "target " + metricsOf(client, d).targetKbps() + " kbps"));
        metrics.add(new Widgets.MetricCard("Average", () -> kbps(metricsOf(client, d).averageKbps()),
                () -> upload ? uploadColor(metricsOf(client, d).averageKbps(), metricsOf(client, d).targetKbps()) : Theme.TEXT,
                () -> upload ? "delivered to the service" : "encoded locally"));
        metrics.add(new Widgets.MetricCard("Encoder", () -> {
            StreamTestMetrics m = metricsOf(client, d);
            return m.encoderFps() < 0 ? "-" : String.format(Locale.ROOT, "%.0f FPS", m.encoderFps());
        }, () -> {
            StreamTestMetrics m = metricsOf(client, d);
            return m.encoderFps() >= 0 && m.encoderFps() < m.targetFps() * 0.95 ? Theme.WARNING : Theme.TEXT;
        }, () -> "target " + metricsOf(client, d).targetFps() + " FPS"));
        metrics.add(new Widgets.MetricCard("Latency", () -> {
            StreamTestMetrics m = metricsOf(client, d);
            return m.encodeLatencyMillis() < 0 ? "-" : String.format(Locale.ROOT, "%.0f ms", m.encodeLatencyMillis());
        }, () -> Theme.TEXT, () -> "frame to encoded"));
        metrics.add(new Widgets.MetricCard("Dropped", () -> Long.toString(metricsOf(client, d).droppedFrames()),
                () -> metricsOf(client, d).droppedFrames() > 0 ? Theme.WARNING : Theme.SUCCESS,
                () -> String.format(Locale.ROOT, "queue peak %.0f%%", metricsOf(client, d).maxQueuePressure() * 100)));

        card.add(new Label(() -> checksText(metricsOf(client, d).networkChecks()))
                .color(Theme.TEXT_SECONDARY).scale(Theme.TEXT_CAPTION).wrap())
                .visibleWhen(() -> sessionFor(client, d) != null && !metricsOf(client, d).networkChecks().isEmpty());
        card.add(new Label(() -> {
            List<String> findings = metricsOf(client, d).findings();
            return findings.isEmpty() ? "" : "• " + String.join("\n• ", findings);
        }).color(Theme.TEXT).wrap()).visibleWhen(() -> {
            StreamTestSession session = sessionFor(client, d);
            List<String> findings = metricsOf(client, d).findings();
            // A failure's single finding is the message already shown above.
            return session != null && !findings.isEmpty()
                    && !(findings.size() == 1 && findings.getFirst().equals(session.message()));
        });
    }

    private static StreamTestMetrics metricsOf(StreamAbleClient client, StreamDestination d) {
        StreamTestSession session = sessionFor(client, d);
        return session == null ? StreamTestMetrics.EMPTY : session.metrics();
    }

    private static String kbps(double value) {
        return value < 0 ? "-" : String.format(Locale.ROOT, "%,d kbps", Math.round(value));
    }

    private static int uploadColor(double kbps, int targetKbps) {
        if (kbps < 0 || targetKbps <= 0) {
            return Theme.TEXT;
        }
        double ratio = kbps / targetKbps;
        return ratio >= 0.9 ? Theme.SUCCESS : ratio >= 0.7 ? Theme.WARNING : Theme.DANGER;
    }

    private static String checksText(List<NetworkProbe.Check> checks) {
        StringBuilder text = new StringBuilder();
        for (NetworkProbe.Check check : checks) {
            if (!text.isEmpty()) {
                text.append("   ");
            }
            String mark = switch (check.status()) {
                case PASSED -> "✓";
                case FAILED -> "✗";
                case SKIPPED -> "–";
            };
            text.append(mark).append(' ').append(check.name());
            if (check.millis() >= 0 && check.status() == NetworkProbe.Status.PASSED) {
                text.append(String.format(Locale.ROOT, " %.0f ms", check.millis()));
            }
            if (check.status() == NetworkProbe.Status.FAILED && check.detail() != null) {
                text.append(" (").append(check.detail()).append(')');
            }
        }
        return text.toString();
    }

    private static String stateLabel(StreamTestSession.State state) {
        return switch (state) {
            case IDLE -> "Not run";
            case PREPARING -> "Preparing";
            case CONNECTING -> "Connecting";
            case TESTING -> "Testing";
            case FINALIZING -> "Finishing";
            case COMPLETE -> "Complete";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
        };
    }

    private static int stateColor(StreamTestSession.State state) {
        return switch (state) {
            case COMPLETE -> Theme.SUCCESS;
            case FAILED -> Theme.DANGER;
            case CANCELLED, IDLE -> Theme.TEXT_MUTED;
            default -> Theme.INFO;
        };
    }
}
