package dev.streamable.ui.studio;

import dev.streamable.StreamAbleClient;
import dev.streamable.browser.BrowserRuntime;
import dev.streamable.config.RuntimeSettings;
import dev.streamable.ffmpeg.FFmpegCapabilityProbe;
import dev.streamable.ffmpeg.FFmpegRuntime;
import dev.streamable.runtime.ManagedRuntime;
import dev.streamable.runtime.RuntimeArtifact;
import dev.streamable.runtime.RuntimeProgress;
import dev.streamable.runtime.RuntimeState;
import dev.streamable.ui.kit.Button;
import dev.streamable.ui.kit.Icons;
import dev.streamable.ui.kit.Label;
import dev.streamable.ui.kit.Layouts;
import dev.streamable.ui.kit.Painter;
import dev.streamable.ui.kit.TextField;
import dev.streamable.ui.kit.Theme;
import dev.streamable.ui.kit.Toggle;
import dev.streamable.ui.kit.UiNode;
import dev.streamable.ui.kit.Widgets;

import java.util.List;
import java.util.Locale;

/**
 * Components: the native runtimes Stream-able downloads on demand - FFmpeg,
 * the Chromium browser engine, ONNX Runtime and the noise models - with their
 * state, progress, source, licence, and install / retry / cancel.
 *
 * <p>Every download is pinned by version and SHA-256 in the bundled
 * manifest, fetched over HTTPS, and installed atomically.</p>
 */
final class RuntimePage {

    private RuntimePage() {
    }

    static void build(Studio s, Layouts.Column page) {
        StreamAbleClient client = s.client();
        RuntimeSettings settings = client.config().runtime;

        Widgets.Card general = page.add(new Widgets.Card(Theme.SPACE_5));
        general.add(new Widgets.SectionHeader("Downloads", () -> "Pinned versions, verified with SHA-256, installed "
                + "into " + client.runtimes().context().root()));
        general.add(Toggle.of("Install needed components automatically", () -> settings.autoInstall, v -> {
            settings.autoInstall = v;
            s.changed();
        }).detail(() -> "Off: nothing is downloaded until you press Install below."));

        for (ManagedRuntime runtime : client.runtimes().all()) {
            runtimeCard(s, page.add(new Widgets.Card(Theme.SPACE_4)), runtime);
        }
    }

    private static void runtimeCard(Studio s, Widgets.Card card, ManagedRuntime runtime) {
        StreamAbleClient client = s.client();
        Layouts.Row head = card.add(new Layouts.Row(Theme.SPACE_3));
        head.add(new Widgets.SectionHeader(runtime.displayName(), () -> "Version " + runtime.version()
                + sizeText(runtime)), -1);
        head.add(new Widgets.StatusPill(() -> runtime.progress().summary(), () -> stateColor(runtime.state()),
                runtime.state().isBusy()), 150);
        card.add(new Widgets.ProgressBar(() -> runtime.progress().fraction(), () -> Theme.ACCENT))
                .visibleWhen(() -> runtime.state().isBusy());
        card.add(new Widgets.Notice(() -> runtime.state() == RuntimeState.FAILED ? runtime.progress().detail() : null,
                () -> Theme.DANGER));
        card.add(new Widgets.Notice(() -> runtime.state() == RuntimeState.UNSUPPORTED
                ? "No build is published for this operating system and processor." : null, () -> Theme.INFO));
        card.add(new Label(() -> "Licence: " + runtime.descriptor().license()).color(Theme.TEXT_SECONDARY)
                .scale(Theme.TEXT_CAPTION).wrap());
        card.add(new Label(() -> "Source: " + runtime.descriptor().source()).color(Theme.TEXT_MUTED)
                .scale(Theme.TEXT_CAPTION).wrap());
        card.add(new Label(() -> runtime.activeDirectory().map(p -> "Installed at " + p).orElse(""))
                .color(Theme.TEXT_MUTED).scale(Theme.TEXT_CAPTION).wrap()).visibleWhen(() -> runtime.activeDirectory().isPresent());

        Layouts.Row actions = card.add(new Layouts.Row(Theme.SPACE_3));
        actions.add(new Button(() -> runtime.state() == RuntimeState.FAILED ? "Retry"
                : runtime.state() == RuntimeState.UPDATE_AVAILABLE ? "Update" : "Install", () -> install(s, runtime))
                .icon(Icons.Icon.DOWN).variant(Button.Variant.PRIMARY)
                .visibleWhen(() -> runtime.artifact().isPresent() && !runtime.state().isBusy()
                        && runtime.state() != RuntimeState.READY), 80);
        actions.add(Button.of("Cancel", runtime::cancel).variant(Button.Variant.GHOST)
                .visibleWhen(() -> runtime.state().isBusy()), 64);
        actions.add(Button.of("Verify", () -> {
            RuntimeState state = runtime.refreshFromDisk();
            if (state == RuntimeState.READY) {
                s.toast(runtime.displayName() + " is installed and intact.");
            } else {
                s.error(runtime.displayName() + ": " + runtime.progress().summary());
            }
        }).variant(Button.Variant.GHOST).tooltip("Re-checks the installed files against the install receipt.")
                .visibleWhen(() -> !runtime.state().isBusy()), 64);
        actions.add(Layouts.spacer(0), -1);

        if (runtime instanceof FFmpegRuntime) {
            ffmpegDetails(s, card);
        } else if (runtime instanceof BrowserRuntime) {
            card.add(Toggle.of("Enable browser sources", () -> client.config().runtime.browserEngineEnabled, v -> {
                client.config().runtime.browserEngineEnabled = v;
                s.changed();
                if (v) {
                    client.installBrowserEngine();
                }
            }).detail(() -> "Status: " + client.browsers().status().shortLabel()
                    + ". Turning it off takes effect after a restart."));
        }
    }

    private static void install(Studio s, ManagedRuntime runtime) {
        StreamAbleClient client = s.client();
        if (runtime instanceof FFmpegRuntime) {
            client.installFfmpeg();
        } else if (runtime instanceof BrowserRuntime) {
            client.installBrowserEngine();
        } else {
            runtime.ensureReady();
        }
        s.toast("Installing " + runtime.displayName() + " in the background.");
    }

    private static String sizeText(ManagedRuntime runtime) {
        return runtime.artifact().map(RuntimeArtifact::size).filter(size -> size > 0)
                .map(size -> String.format(Locale.ROOT, " · %.0f MB download", size / 1_048_576.0)).orElse("");
    }

    private static int stateColor(RuntimeState state) {
        return switch (state) {
            case READY -> Theme.SUCCESS;
            case FAILED -> Theme.DANGER;
            case UPDATE_AVAILABLE -> Theme.WARNING;
            case NOT_INSTALLED, UNSUPPORTED -> Theme.TEXT_MUTED;
            default -> Theme.INFO;
        };
    }

    private static void ffmpegDetails(Studio s, Widgets.Card card) {
        StreamAbleClient client = s.client();
        RuntimeSettings settings = client.config().runtime;
        card.add(new Widgets.Divider());
        card.add(new Label(() -> {
            var resolution = client.ffmpeg().resolution();
            return resolution.isAvailable()
                    ? "In use: " + Studio.shortVersion(resolution.version()) + " - " + resolution.describe()
                    : "No FFmpeg is available yet.";
        }).color(Theme.TEXT_SECONDARY).wrap());
        card.add(new TextField("Use a specific FFmpeg instead (optional)", () -> settings.ffmpegOverridePath, v -> {
            settings.ffmpegOverridePath = v.strip();
            client.ffmpeg().setConfiguredPath(settings.ffmpegOverridePath);
            s.changed();
            client.onFfmpegChanged();
        }).commitOnBlur().placeholder("Full path to an ffmpeg executable").maxLength(1024));
        card.add(Toggle.of("Fall back to FFmpeg on the system PATH", () -> settings.allowSystemFfmpeg, v -> {
            settings.allowSystemFfmpeg = v;
            s.changed();
            client.onFfmpegChanged();
        }));

        Layouts.Row probe = card.add(new Layouts.Row(Theme.SPACE_3));
        probe.add(new Widgets.Caption("Encoders tested on this computer"), -1);
        probe.add(Button.of("Test again", () -> {
            client.onFfmpegChanged();
            s.toast("Re-testing encoders...");
        }).icon(Icons.Icon.REFRESH).variant(Button.Variant.GHOST), 90);
        card.add(new EncoderTable(client.encoderProbe()));
    }

    /** One line per encoder: usable or not, test-encode speed, and why not. */
    private static final class EncoderTable extends UiNode {
        private final FFmpegCapabilityProbe probe;

        EncoderTable(FFmpegCapabilityProbe probe) {
            this.probe = probe;
        }

        @Override
        public int preferredHeight(int availableWidth) {
            return probe.isComplete() ? Math.max(12, probe.results().size() * 12) : 12;
        }

        @Override
        protected void renderSelf(Painter p) {
            if (!probe.isComplete()) {
                p.text("Testing...", x, y, Theme.TEXT_MUTED);
                return;
            }
            List<FFmpegCapabilityProbe.Result> results = probe.results();
            int cy = y;
            for (FFmpegCapabilityProbe.Result r : results) {
                p.circle(x + 4, cy + 4.5f, 2.5f, r.usable() ? Theme.SUCCESS : Theme.TEXT_DISABLED);
                p.textClipped(r.encoder().displayName(), x + 12, cy, 150, r.usable() ? Theme.TEXT : Theme.TEXT_MUTED,
                        1f, Painter.Weight.REGULAR);
                String detail = r.usable()
                        ? (r.framesPerSecond() > 0 ? String.format(Locale.ROOT, "%.0f FPS test encode", r.framesPerSecond()) : "works")
                        : r.reason();
                p.textClipped(detail, x + 170, cy + 1, width - 170, Theme.TEXT_MUTED, Theme.TEXT_CAPTION,
                        Painter.Weight.REGULAR);
                cy += 12;
            }
        }
    }
}
