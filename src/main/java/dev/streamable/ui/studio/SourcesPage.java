package dev.streamable.ui.studio;

import dev.streamable.StreamAbleClient;
import dev.streamable.browser.BrowserEngineStatus;
import dev.streamable.browser.BrowserTestPage;
import dev.streamable.browser.audio.BrowserAudioBridge;
import dev.streamable.source.BrowserAudioMode;
import dev.streamable.source.BrowserSource;
import dev.streamable.source.transform.SourceTransform;
import dev.streamable.ui.SourceEditorScreen;
import dev.streamable.ui.kit.Button;
import dev.streamable.ui.kit.Dropdown;
import dev.streamable.ui.kit.IconButton;
import dev.streamable.ui.kit.Icons;
import dev.streamable.ui.kit.Label;
import dev.streamable.ui.kit.Layouts;
import dev.streamable.ui.kit.Painter;
import dev.streamable.ui.kit.Segmented;
import dev.streamable.ui.kit.Slider;
import dev.streamable.ui.kit.TextField;
import dev.streamable.ui.kit.Theme;
import dev.streamable.ui.kit.Toggle;
import dev.streamable.ui.kit.UiNode;
import dev.streamable.ui.kit.Widgets;
import dev.streamable.video.Resolution;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Sources: the browser source list and the selected source's properties. */
final class SourcesPage {

    private SourcesPage() {
    }

    static void build(Studio s, Layouts.Column page) {
        StreamAbleClient client = s.client();

        page.add(new Widgets.Notice(() -> engineNotice(client.browsers().status()), () -> {
            BrowserEngineStatus.State state = client.browsers().status().state();
            return state == BrowserEngineStatus.State.FAILED ? Theme.DANGER : Theme.WARNING;
        }));
        Layouts.Row engineActions = page.add(new Layouts.Row(Theme.SPACE_3));
        engineActions.add(Button.of("Install / retry browser engine", () -> {
            client.installBrowserEngine();
            s.toast("Installing the browser engine in the background.");
        }).icon(Icons.Icon.REFRESH), 190);
        engineActions.add(Button.of("Components", () -> s.screen().show(Page.RUNTIME)).variant(Button.Variant.GHOST), 96);
        engineActions.add(Layouts.spacer(0), -1);
        engineActions.visibleWhen(() -> {
            BrowserEngineStatus.State state = client.browsers().status().state();
            return state == BrowserEngineStatus.State.NOT_INSTALLED || state == BrowserEngineStatus.State.FAILED
                    || state == BrowserEngineStatus.State.DISABLED;
        });

        Layouts.Row actions = page.add(new Layouts.Row(Theme.SPACE_3));
        actions.add(Button.of("Add browser source", () -> {
            BrowserSource created = client.addBrowserSource("Browser Source", "about:blank");
            client.editor().select(created.id());
            s.screen().refresh();
        }).variant(Button.Variant.PRIMARY).icon(Icons.Icon.PLUS), 132);
        actions.add(Button.of("Canvas editor", () -> net.minecraft.client.Minecraft.getInstance().setScreen(new SourceEditorScreen(client)))
                .icon(Icons.Icon.GRID).tooltip("Drag, resize and rotate sources over the game (F7)."), 104);
        actions.add(Button.of("Add test page", () -> {
            String url = BrowserTestPage.extractAndGetUrl(FabricLoader.getInstance().getConfigDir());
            if (url == null) {
                s.error("Could not write the browser test page.");
                return;
            }
            BrowserSource created = client.addBrowserSource("Browser test page", url);
            client.editor().select(created.id());
            s.screen().refresh();
        }).variant(Button.Variant.GHOST).tooltip("Adds a local page that checks transparency, clicks and typing."), 96);
        actions.add(Layouts.spacer(0), -1);

        List<BrowserSource> sources = client.sources().displayOrder();
        Widgets.Card list = page.add(new Widgets.Card(Theme.SPACE_2));
        list.add(new Widgets.Caption("Sources (top of the list draws on top)"));
        if (sources.isEmpty()) {
            list.add(new Label(() -> "No sources yet. Add a browser source for alerts, chat or overlays.")
                    .color(Theme.TEXT_MUTED).wrap());
        }
        for (BrowserSource source : sources) {
            list.add(new SourceRow(s, source));
        }

        BrowserSource selected = client.editor().selected();
        if (selected != null) {
            properties(s, page.add(new Widgets.Card(Theme.SPACE_5)), selected);
        }
    }

    private static String engineNotice(BrowserEngineStatus status) {
        return switch (status.state()) {
            case READY -> null;
            case INITIALISING -> "Browser engine: " + status.shortLabel() + ". Sources appear when it is ready.";
            case DISABLED -> "The browser engine is turned off, so browser sources are not rendered.";
            case NOT_INSTALLED -> "Browser sources need the embedded Chromium engine (about 150 MB, verified "
                    + "download). Recording and streaming work without it.";
            case FAILED -> "The browser engine could not start: " + status.detail();
        };
    }

    /** One source in the list: visibility, lock, name, order. Click to select. */
    private static final class SourceRow extends UiNode {
        private final Studio studio;
        private final BrowserSource source;

        SourceRow(Studio s, BrowserSource source) {
            this.studio = s;
            this.source = source;
            StreamAbleClient client = s.client();
            add(new IconButton(() -> source.visible() ? Icons.Icon.EYE : Icons.Icon.EYE_OFF,
                    "Show or hide this source everywhere", () -> {
                source.setVisible(!source.visible());
                s.changed();
            }));
            add(new IconButton(() -> source.locked() ? Icons.Icon.LOCK : Icons.Icon.UNLOCK,
                    "Lock the source so the canvas editor cannot move it", () -> {
                source.setLocked(!source.locked());
                s.changed();
            }).activeWhen(source::locked, Theme.WARNING));
            add(IconButton.of(Icons.Icon.UP, "Move up (draws above more sources)", () -> {
                client.sources().moveUp(source.id());
                s.changed();
                s.screen().refresh();
            }));
            add(IconButton.of(Icons.Icon.DOWN, "Move down", () -> {
                client.sources().moveDown(source.id());
                s.changed();
                s.screen().refresh();
            }));
        }

        private boolean selected() {
            return source.id().equals(studio.client().editor().selectedId());
        }

        @Override
        public boolean isFocusable() {
            return true;
        }

        @Override
        public int preferredHeight(int availableWidth) {
            return 18;
        }

        @Override
        protected void layout() {
            children.get(0).setBounds(x + 2, y + 1, 16, 16);
            children.get(1).setBounds(x + 20, y + 1, 16, 16);
            children.get(3).setBounds(x + width - 18, y + 1, 16, 16);
            children.get(2).setBounds(x + width - 36, y + 1, 16, 16);
        }

        @Override
        protected void renderSelf(Painter p) {
            int bg = selected() ? Theme.ACCENT_SOFT : Theme.withAlpha(Theme.SURFACE_HOVER, hover.eased());
            p.roundRect(x, y, width, height, Theme.RADIUS, bg);
            int color = source.visible() ? Theme.TEXT : Theme.TEXT_MUTED;
            String routing = routingSummary(source);
            int rw = p.textWidth(routing, Theme.TEXT_CAPTION, Painter.Weight.REGULAR);
            p.textClipped(source.name(), x + 42, y + 5, width - 90 - rw, color, 1f,
                    selected() ? Painter.Weight.SEMIBOLD : Painter.Weight.REGULAR);
            p.text(routing, x + width - 42 - rw, y + 6, Theme.TEXT_MUTED, Theme.TEXT_CAPTION, Painter.Weight.REGULAR);
        }

        @Override
        public boolean mouseDown(double mx, double my, int button) {
            if (button == 0) {
                activate();
                return true;
            }
            return false;
        }

        @Override
        public boolean activate() {
            studio.client().editor().select(source.id());
            studio.screen().refresh();
            return true;
        }
    }

    static String routingSummary(BrowserSource source) {
        var r = source.routing();
        List<String> where = new ArrayList<>();
        if (r.showLocally()) {
            where.add("Screen");
        }
        if (r.includeInRecording()) {
            where.add("Rec");
        }
        if (r.includeInStream()) {
            where.add("Stream");
        }
        return where.isEmpty() ? "Nowhere" : String.join(" · ", where);
    }

    private static void properties(Studio s, Widgets.Card card, BrowserSource source) {
        StreamAbleClient client = s.client();
        Layouts.Row head = card.add(new Layouts.Row(Theme.SPACE_3));
        head.add(new Widgets.SectionHeader("Properties", () -> source.kind().name().charAt(0)
                + source.kind().name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' ') + " source"), -1);
        head.add(Button.of("Reload", () -> client.browsers().refresh(source.id(), true)).icon(Icons.Icon.REFRESH)
                .tooltip("Reload the page, bypassing the cache."), 64);
        head.add(Button.of("Duplicate", () -> {
            BrowserSource copy = client.duplicateSource(source.id());
            if (copy != null) {
                client.editor().select(copy.id());
            }
            s.screen().refresh();
        }).icon(Icons.Icon.COPY), 76);
        head.add(Button.of("Delete", () -> s.screen().confirm("Delete \"" + source.name() + "\"?",
                "This removes the source and its settings.", "Delete", true, () -> {
                    client.removeSource(source.id());
                    s.screen().refresh();
                })).variant(Button.Variant.DANGER), 60);

        Layouts.Grid identity = card.add(new Layouts.Grid(200, Theme.SPACE_5));
        identity.add(new TextField("Name", source::name, v -> {
            source.setName(v);
            s.changed();
        }).maxLength(64));
        identity.add(new TextField("URL", source::url, v -> {
            source.setUrl(v.strip());
            s.changed();
        }).commitOnBlur().maxLength(2048).placeholder("https://..."))
                .tooltip("Applied when you press Enter or leave the field.");

        card.add(new Widgets.Caption("Position and size (canvas pixels)"));
        Layouts.Grid transform = card.add(new Layouts.Grid(70, Theme.SPACE_4));
        transform.add(s.doubleField("X", () -> source.transform().x(),
                v -> source.setTransform(source.transform().withPosition(v, source.transform().y())), -16384, 16384));
        transform.add(s.doubleField("Y", () -> source.transform().y(),
                v -> source.setTransform(source.transform().withPosition(source.transform().x(), v)), -16384, 16384));
        transform.add(s.doubleField("Width", () -> source.transform().width(),
                v -> source.setTransform(source.transform().withSize(v, source.transform().height())),
                SourceTransform.MIN_SIZE, SourceTransform.MAX_SIZE));
        transform.add(s.doubleField("Height", () -> source.transform().height(),
                v -> source.setTransform(source.transform().withSize(source.transform().width(), v)),
                SourceTransform.MIN_SIZE, SourceTransform.MAX_SIZE));
        transform.add(s.doubleField("Rotation", () -> source.transform().rotation(),
                v -> source.setTransform(new SourceTransform(source.transform().x(), source.transform().y(),
                        source.transform().width(), source.transform().height(), v)), -360, 360));
        Layouts.Row place = card.add(new Layouts.Row(Theme.SPACE_3));
        place.add(Button.of("Fill canvas", () -> {
            Resolution canvas = client.canvasResolution();
            source.setTransform(SourceTransform.of(0, 0, canvas.width(), canvas.height()));
            s.changed();
        }).variant(Button.Variant.GHOST), 78);
        place.add(Button.of("Center", () -> {
            Resolution canvas = client.canvasResolution();
            SourceTransform t = source.transform();
            source.setTransform(t.withPosition((canvas.width() - t.width()) / 2.0, (canvas.height() - t.height()) / 2.0));
            s.changed();
        }).variant(Button.Variant.GHOST), 60);
        place.add(Layouts.spacer(0), -1);
        card.add(new Slider("Opacity", 0, 1, 0.01, source::opacity, v -> {
            source.setOpacity((float) v);
            s.changed();
        }).format(v -> Math.round(v * 100) + "%").defaultValue(1));

        card.add(new Widgets.Caption("Visible on"));
        Layouts.Grid routing = card.add(new Layouts.Grid(120, Theme.SPACE_5));
        routing.add(Toggle.of("My screen", () -> source.routing().showLocally(), v -> {
            source.setRouting(source.routing().withShowLocally(v));
            s.changed();
        }).tooltip("Draw it over your own game view."));
        routing.add(Toggle.of("Recording", () -> source.routing().includeInRecording(), v -> {
            source.setRouting(source.routing().withIncludeInRecording(v));
            s.changed();
        }));
        routing.add(Toggle.of("Stream", () -> source.routing().includeInStream(), v -> {
            source.setRouting(source.routing().withIncludeInStream(v));
            s.changed();
        }));

        card.add(new Widgets.Caption("Browser"));
        Layouts.Grid browser = card.add(new Layouts.Grid(160, Theme.SPACE_5));
        List<String> fpsLabels = new ArrayList<>();
        for (int fps : BrowserSource.FPS_CHOICES) {
            fpsLabels.add(fps + " FPS");
        }
        browser.add(new Segmented(fpsLabels, () -> indexOf(BrowserSource.FPS_CHOICES, source.browserFps()), i -> {
            source.setBrowserFps(BrowserSource.FPS_CHOICES[i]);
            s.changed();
        }).tooltip("How often the page repaints. Lower saves CPU for static overlays."));
        List<BrowserAudioMode> modes = audioModes(source);
        browser.add(new Dropdown("Page audio", () -> modes.stream().map(BrowserAudioMode::displayName).toList(),
                () -> modes.indexOf(source.audioMode()), i -> {
            source.setAudioMode(modes.get(i));
            s.changed();
        }));
        browser.add(Toggle.of("Reload when shown", source::refreshOnActivate, v -> {
            source.setRefreshOnActivate(v);
            s.changed();
        }));
        browser.add(Toggle.of("Unload when hidden", source::shutdownWhenHidden, v -> {
            source.setShutdownWhenHidden(v);
            s.changed();
        }).tooltip("Frees memory while hidden; the page restarts when shown again."));
        card.add(new Widgets.Notice(() -> BrowserAudioBridge.describeLimitation(source), () -> Theme.WARNING));
        card.add(new Widgets.Notice(() -> BrowserAudioBridge.canCaptureBrowserAudio() ? null
                : "Page audio plays on your speakers only. " + BrowserAudioBridge.limitationReason(), () -> Theme.INFO));

        Layouts.Row css = card.add(new Layouts.Row(Theme.SPACE_3));
        css.add(Button.of("Transparent background", () -> {
            source.setCustomCss(BrowserSource.TRANSPARENT_CSS_PRESET);
            s.changed();
            s.toast("Transparent background CSS applied.");
        }).tooltip("Injects CSS that removes the page background, for overlays."), 150);
        css.add(Button.of("Clear custom CSS", () -> {
            source.setCustomCss("");
            s.changed();
        }).variant(Button.Variant.GHOST).enabledWhen(() -> !source.customCss().isEmpty()), 110);
        css.add(Layouts.spacer(0), -1);
    }

    /** Modes the embedded browser can actually honour, plus the source's current one. */
    private static List<BrowserAudioMode> audioModes(BrowserSource source) {
        List<BrowserAudioMode> modes = new ArrayList<>();
        for (BrowserAudioMode mode : BrowserAudioMode.values()) {
            boolean streams = mode == BrowserAudioMode.STREAM_ONLY || mode == BrowserAudioMode.MONITOR_AND_STREAM;
            if (!streams || BrowserAudioBridge.canCaptureBrowserAudio() || mode == source.audioMode()) {
                modes.add(mode);
            }
        }
        return modes;
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return 0;
    }
}
