package dev.streamable.ui.studio;

import dev.streamable.StreamAble;
import dev.streamable.StreamAbleClient;
import dev.streamable.config.InterfaceSettings;
import dev.streamable.config.LegacyRecordableImport;
import dev.streamable.diagnostics.ClientDiagnostics;
import dev.streamable.ui.kit.Button;
import dev.streamable.ui.kit.Icons;
import dev.streamable.ui.kit.Label;
import dev.streamable.ui.kit.Layouts;
import dev.streamable.ui.kit.Painter;
import dev.streamable.ui.kit.Slider;
import dev.streamable.ui.kit.Theme;
import dev.streamable.ui.kit.Toggle;
import dev.streamable.ui.kit.UiNode;
import dev.streamable.ui.kit.Widgets;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Advanced: editor and HUD preferences, shortcuts, diagnostics and licences. */
final class AdvancedPage {

    private AdvancedPage() {
    }

    static void build(Studio s, Layouts.Column page) {
        StreamAbleClient client = s.client();
        InterfaceSettings ui = client.config().ui;

        Widgets.Card editor = page.add(new Widgets.Card(Theme.SPACE_5));
        editor.add(new Widgets.SectionHeader("Canvas editor", () -> "Snapping while you drag sources (F7)"));
        editor.add(Toggle.of("Snap to other sources", () -> ui.snapToOtherSources, v -> {
            ui.snapToOtherSources = v;
            client.applyInterfaceSettings();
            s.changed();
        }));
        editor.add(new Slider("Snap distance", 0, 32, 1, () -> ui.snapThreshold, v -> {
            ui.snapThreshold = v;
            client.applyInterfaceSettings();
            s.changed();
        }).format(v -> Math.round(v) + " px").defaultValue(8));
        editor.add(Toggle.of("Browser pages use premultiplied alpha", () -> ui.premultipliedBrowserAlpha, v -> {
            ui.premultipliedBrowserAlpha = v;
            client.applyInterfaceSettings();
            s.changed();
        }).detail(() -> "Leave on. Turn off only if transparent overlays show dark fringes."));

        Widgets.Card hud = page.add(new Widgets.Card(Theme.SPACE_5));
        hud.add(new Widgets.SectionHeader("Stream HUD", () -> "The small live status panel on your screen only"));
        hud.add(Toggle.of("Show while recording or live", () -> ui.showStreamHud, v -> {
            ui.showStreamHud = v;
            s.changed();
        }));
        hud.add(Toggle.of("Detailed", () -> ui.detailedStreamHud, v -> {
            ui.detailedStreamHud = v;
            s.changed();
        }));
        hud.add(new Slider("Size", 0.5, 2, 0.05, () -> ui.streamHudScale, v -> {
            ui.streamHudScale = (float) v;
            s.changed();
        }).format(v -> Math.round(v * 100) + "%").defaultValue(1));
        hud.add(new Slider("Opacity", 0.2, 1, 0.05, () -> ui.streamHudOpacity, v -> {
            ui.streamHudOpacity = (float) v;
            s.changed();
        }).format(v -> Math.round(v * 100) + "%").defaultValue(0.85));
        hud.add(Button.of("Reset position", () -> {
            ui.streamHudX = -1;
            ui.streamHudY = -1;
            s.changed();
        }).variant(Button.Variant.GHOST).tooltip("Drag the HUD in the canvas editor (F7) to move it."));

        Widgets.Card keys = page.add(new Widgets.Card(Theme.SPACE_3));
        keys.add(new Widgets.SectionHeader("Keyboard", () -> "Change keys in Options > Controls > Key Binds > Stream-able"));
        for (KeyMapping mapping : StreamAble.keyMappings()) {
            keys.add(new KeyRow(Component.translatable(mapping.getName()).getString(),
                    () -> mapping.isUnbound() ? "Not set" : mapping.getTranslatedKeyMessage().getString()));
        }
        keys.add(new KeyRow("Studio: switch page", () -> "Ctrl+1 ... Ctrl+0"));
        keys.add(new KeyRow("Studio: start / stop recording", () -> "Ctrl+R"));
        keys.add(new KeyRow("Studio: move between controls", () -> "Tab / Shift+Tab, Enter or Space"));
        keys.add(new KeyRow("Studio: close popup, leave field, close", () -> "Esc"));

        Widgets.Card support = page.add(new Widgets.Card(Theme.SPACE_5));
        support.add(new Widgets.SectionHeader("Support", () -> "For bug reports"));
        Layouts.Row actions = support.add(new Layouts.Row(Theme.SPACE_3));
        actions.add(Button.of("Copy diagnostics", () -> s.copyToClipboard(
                ClientDiagnostics.collect(client, modVersion()), "Diagnostics copied (no stream keys).")).icon(Icons.Icon.COPY)
                .tooltip("Versions, GPU, FFmpeg, encoders, components, settings and health - with every stream key "
                        + "and URL credential removed."), 130);
        actions.add(Button.of("Open logs folder", () -> {
            try {
                net.minecraft.util.Util.getPlatform().openPath(FabricLoader.getInstance().getGameDir().resolve("logs"));
            } catch (RuntimeException e) {
                s.error("Could not open the logs folder.");
            }
        }).variant(Button.Variant.GHOST), 110);
        actions.add(Layouts.spacer(0), -1);
        if (LegacyRecordableImport.isAvailable(FabricLoader.getInstance().getConfigDir(), client.config())) {
            support.add(Button.of("Import Record-able settings", () -> {
                LegacyRecordableImport.Result result = LegacyRecordableImport.importInto(
                        FabricLoader.getInstance().getConfigDir(), client.config());
                client.saveNow();
                if (result.success()) {
                    s.toast(result.message());
                } else {
                    s.error(result.message());
                }
                s.screen().refresh();
            }).tooltip("Copies recording settings from an existing Record-able install."));
        }

        Widgets.Card about = page.add(new Widgets.Card(Theme.SPACE_4));
        about.add(new Widgets.SectionHeader("About Stream-able " + modVersion(),
                () -> "Recording and livestreaming inside Minecraft. MIT licence."));
        for (String line : List.of(
                "Derived from Record-able by JoEusebe (MIT).",
                "Browser sources: MCEF Modern (LGPL-2.1, bundled unmodified), JCEF and the Chromium Embedded "
                        + "Framework (BSD-3-Clause); Chromium's own licences apply to the downloaded engine.",
                "FFmpeg: a GPL build by BtbN, downloaded on demand and run as a separate program; its source is "
                        + "available from the FFmpeg project and BtbN's build scripts.",
                "Noise cancellation: ONNX Runtime (MIT); DPDFNet (Apache-2.0); DeepFilterNet2 (MIT or Apache-2.0); "
                        + "GTCRN (MIT). Models are downloaded on demand and run locally.",
                "Interface font: Inter (SIL Open Font License 1.1). Archive decoding: XZ for Java (0BSD).",
                "Full notices are in the NOTICE file shipped with the mod.")) {
            about.add(new Label(() -> line).color(Theme.TEXT_SECONDARY).scale(Theme.TEXT_CAPTION).wrap());
        }
    }

    static String modVersion() {
        return FabricLoader.getInstance().getModContainer(StreamAble.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }

    private static final class KeyRow extends UiNode {
        private final String name;
        private final java.util.function.Supplier<String> key;

        KeyRow(String name, java.util.function.Supplier<String> key) {
            this.name = name;
            this.key = key;
        }

        @Override
        public int preferredHeight(int availableWidth) {
            return 12;
        }

        @Override
        protected void renderSelf(Painter p) {
            String k = key.get();
            int kw = p.textWidth(k, 1f, Painter.Weight.SEMIBOLD) + 8;
            p.textClipped(name, x, y + 2, width - kw - 8, Theme.TEXT_SECONDARY, 1f, Painter.Weight.REGULAR);
            p.roundRect(x + width - kw, y, kw, 12, Theme.RADIUS_SMALL, Theme.SURFACE_RAISED);
            p.text(k, x + width - kw + 4, y + 2, k.equals("Not set") ? Theme.TEXT_MUTED : Theme.TEXT, 1f,
                    Painter.Weight.SEMIBOLD);
        }
    }
}
