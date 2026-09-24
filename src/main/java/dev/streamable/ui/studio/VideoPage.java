package dev.streamable.ui.studio;

import dev.streamable.StreamAbleClient;
import dev.streamable.config.VideoSettings;
import dev.streamable.ffmpeg.VideoEncoder;
import dev.streamable.ui.kit.Button;
import dev.streamable.ui.kit.Dropdown;
import dev.streamable.ui.kit.Label;
import dev.streamable.ui.kit.Layouts;
import dev.streamable.ui.kit.Theme;
import dev.streamable.ui.kit.Toggle;
import dev.streamable.ui.kit.Widgets;
import dev.streamable.video.OutputTransform;
import dev.streamable.video.OutputValidation;
import dev.streamable.video.Resolution;
import dev.streamable.video.ResolutionPresets;
import dev.streamable.video.ScalingMode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Video: the program canvas, how the game fills it, and each output's size
 * and scaling. Every combination is validated live; nothing is adjusted
 * silently.
 */
final class VideoPage {

    static final int[] FPS_CHOICES = {24, 30, 48, 50, 60, 72, 90, 120, 144};

    /** Custom canvas size being typed; applied with the button, since it can rescale sources. */
    private static int pendingWidth;
    private static int pendingHeight;
    private static boolean rescaleSources = true;

    private VideoPage() {
    }

    static void build(Studio s, Layouts.Column page) {
        StreamAbleClient client = s.client();
        Resolution canvas = client.canvasResolution();
        pendingWidth = canvas.width();
        pendingHeight = canvas.height();

        page.add(new Widgets.Notice(() -> client.recording().isActive() || client.streaming().isLive()
                ? "Stop recording and streaming to change sizes; the running encoders were sized from these values."
                : null, () -> Theme.INFO));

        canvasCard(s, page.add(new Widgets.Card(Theme.SPACE_5)));
        outputCard(s, page.add(new Widgets.Card(Theme.SPACE_5)), "Recording output", client.config().video.recording,
                OutputValidation.Target.RECORDING);
        outputCard(s, page.add(new Widgets.Card(Theme.SPACE_5)), "Streaming output", client.config().video.streaming,
                OutputValidation.Target.STREAMING);
    }

    private static boolean idle(StreamAbleClient client) {
        return !client.recording().isActive() && !client.streaming().isLive();
    }

    // ---- canvas -------------------------------------------------------------------------

    private static void canvasCard(Studio s, Widgets.Card card) {
        StreamAbleClient client = s.client();
        VideoSettings video = client.config().video;
        card.add(new Widgets.SectionHeader("Program canvas", () -> {
            Resolution canvas = client.canvasResolution();
            return canvas.label() + " · " + canvas.marketedRatio() + " · " + canvas.aspectClass().displayName()
                    + "  -  every source is positioned on this surface; outputs are taken from it.";
        }));

        List<Resolution> presets = ResolutionPresets.all();
        Layouts.Grid pick = card.add(new Layouts.Grid(170, Theme.SPACE_5));
        pick.add(new Dropdown("Preset", () -> presets.stream().map(VideoPage::presetLabel).toList(),
                () -> presets.indexOf(client.canvasResolution()), i -> applyCanvas(s, presets.get(i)))
                .placeholder(() -> "Custom " + client.canvasResolution().label())
                .enabledWhen(() -> idle(client)));
        pick.add(Button.of("Match game window", () -> {
            Resolution game = StreamAbleClient.gameResolution();
            if (game != null) {
                applyCanvas(s, game);
            }
        }).tooltip("Use the current Minecraft window size, e.g. your ultrawide monitor's native resolution.")
                .enabledWhen(() -> idle(client)));

        Layouts.Row custom = card.add(new Layouts.Row(Theme.SPACE_4));
        custom.add(s.intField("Width", () -> pendingWidth, v -> pendingWidth = v, 16, Resolution.MAX_DIMENSION), 70);
        custom.add(s.intField("Height", () -> pendingHeight, v -> pendingHeight = v, 16, Resolution.MAX_DIMENSION), 70);
        custom.add(new Layouts.Custom(27, n -> { }), -1);
        Button apply = custom.add(Button.of("Apply size", () -> {
            Resolution next = Resolution.tryOf(pendingWidth, pendingHeight);
            if (next == null) {
                s.error("That size is outside the supported range.");
                return;
            }
            applyCanvas(s, next);
        }).enabledWhen(() -> idle(client) && !(pendingWidth == client.canvasResolution().width()
                && pendingHeight == client.canvasResolution().height())), 80);
        apply.tooltip("Custom sizes are used exactly as typed.");
        card.add(new Label(() -> {
            Resolution typed = Resolution.tryOf(pendingWidth, pendingHeight);
            Resolution game = StreamAbleClient.gameResolution();
            return (typed == null ? "Enter a size between 16 and 16384 pixels per side." : "Typed: " + typed.label()
                    + " (" + typed.marketedRatio() + ", " + typed.aspectClass().displayName() + ")")
                    + (game == null ? "" : "   ·   Game window: " + game.label() + " (" + game.marketedRatio() + ")");
        }).color(Theme.TEXT_MUTED).scale(Theme.TEXT_CAPTION));
        card.add(Toggle.of("Rescale sources with the canvas", () -> rescaleSources, v -> rescaleSources = v)
                .tooltip("Keeps each source's relative placement when the canvas size changes."));

        card.add(s.enumDropdown("Game on the canvas", ScalingMode.values(), ScalingMode::displayName,
                () -> video.gameScaling, v -> video.gameScaling = v)
                .tooltip("How the game picture fills the canvas when the window and canvas differ in size."));
        card.add(new Label(() -> {
            Resolution game = StreamAbleClient.gameResolution();
            if (game == null) {
                return video.gameScaling.description();
            }
            return OutputTransform.compute(game, client.canvasResolution(), video.gameScaling).describe()
                    + ". " + video.gameScaling.description();
        }).color(Theme.TEXT_SECONDARY).scale(Theme.TEXT_CAPTION).wrap());
        card.add(new Widgets.Notice(() -> {
            Resolution game = StreamAbleClient.gameResolution();
            if (game == null || video.gameScaling != ScalingMode.STRETCH) {
                return null;
            }
            return OutputTransform.compute(game, client.canvasResolution(), ScalingMode.STRETCH).distorts()
                    ? "Stretch distorts the game picture because the window and canvas shapes differ." : null;
        }, () -> Theme.WARNING));
    }

    private static void applyCanvas(Studio s, Resolution next) {
        String error = s.client().setCanvas(next, rescaleSources);
        if (error != null) {
            s.error(error);
            return;
        }
        s.toast("Canvas set to " + next.label() + ".");
        s.screen().refresh();
    }

    static String presetLabel(Resolution r) {
        return r.label() + "  (" + r.marketedRatio() + ")";
    }

    // ---- outputs --------------------------------------------------------------------------

    private static void outputCard(Studio s, Widgets.Card card, String title, VideoSettings.Output output,
                                   OutputValidation.Target target) {
        StreamAbleClient client = s.client();
        boolean streaming = target == OutputValidation.Target.STREAMING;
        card.add(new Widgets.SectionHeader(title, () -> {
            Resolution out = output.resolve(client.canvasResolution());
            return out.label() + " · " + out.marketedRatio() + " · " + output.effectiveMode().displayName()
                    + " · " + fps(client, streaming) + " FPS";
        }));

        card.add(Toggle.of("Same as the canvas", () -> output.matchCanvas, v -> {
            output.matchCanvas = v;
            if (!v) {
                Resolution current = client.canvasResolution().nearestEven();
                output.width = current.width();
                output.height = current.height();
            }
            s.changed();
        }).detail(() -> output.matchCanvas ? "Native, pixel for pixel. Odd canvas sizes are rounded down to even for the encoder."
                : null).enabledWhen(() -> idle(client)));

        List<Resolution> presets = ResolutionPresets.all();
        Layouts.Grid size = card.add(new Layouts.Grid(150, Theme.SPACE_5));
        size.visibleWhen(() -> !output.matchCanvas);
        size.add(new Dropdown("Preset", () -> presets.stream().map(VideoPage::presetLabel).toList(),
                () -> presets.indexOf(new Resolution(Math.max(16, output.width), Math.max(16, output.height))), i -> {
            output.width = presets.get(i).width();
            output.height = presets.get(i).height();
            s.changed();
        }).placeholder(() -> "Custom").enabledWhen(() -> idle(client)));
        Layouts.Row wh = size.add(new Layouts.Row(Theme.SPACE_4));
        wh.add(s.intField("Width", () -> output.width, v -> output.width = v, 16, Resolution.MAX_DIMENSION)
                .enabledWhen(() -> idle(client)), -1);
        wh.add(s.intField("Height", () -> output.height, v -> output.height = v, 16, Resolution.MAX_DIMENSION)
                .enabledWhen(() -> idle(client)), -1);
        size.add(s.enumDropdown("Scaling", ScalingMode.values(), ScalingMode::displayName,
                () -> output.mode, v -> output.mode = v).enabledWhen(() -> idle(client)));
        if (streaming) {
            size.add(Button.of("Use suggested size", () -> {
                Resolution suggested = ResolutionPresets.suggestedStreamOutput(client.canvasResolution());
                output.matchCanvas = false;
                output.width = suggested.width();
                output.height = suggested.height();
                if (output.mode == ScalingMode.NATIVE) {
                    output.mode = ScalingMode.FIT;
                }
                s.changed();
            }).tooltip("The largest common 16:9 size that does not upscale the canvas.")
                    .enabledWhen(() -> idle(client)));
        }

        List<Integer> fpsChoices = new ArrayList<>();
        for (int f : FPS_CHOICES) {
            fpsChoices.add(f);
        }
        IntSupplier fpsGet = streaming ? () -> client.config().streaming.fps : () -> client.config().recording.fps;
        IntConsumer fpsSet = streaming ? v -> client.config().streaming.fps = v : v -> client.config().recording.fps = v;
        card.add(new Dropdown("Frame rate", () -> fpsChoices.stream().map(f -> f + " FPS").toList(),
                () -> fpsChoices.indexOf(fpsGet.getAsInt()), i -> {
            fpsSet.accept(fpsChoices.get(i));
            s.changed();
        }).placeholder(() -> fpsGet.getAsInt() + " FPS").enabledWhen(() -> idle(client))
                .tooltip("Output frames per second. When the game renders slower, frames are repeated so "
                        + "the video keeps real time."));

        card.add(new Label(() -> describe(client, output)).color(Theme.TEXT_SECONDARY).scale(Theme.TEXT_CAPTION).wrap());
        java.util.function.Supplier<List<OutputValidation.Issue>> issues = () -> issues(s, output, target);
        for (int i = 0; i < 4; i++) {
            int index = i;
            card.add(new Widgets.Notice(() -> {
                List<OutputValidation.Issue> list = issues.get();
                return index < list.size() ? list.get(index).message() : null;
            }, () -> {
                List<OutputValidation.Issue> list = issues.get();
                return index < list.size() ? severityColor(list.get(index).severity()) : Theme.INFO;
            }));
        }
    }

    private static int fps(StreamAbleClient client, boolean streaming) {
        return streaming ? client.config().streaming.fps : client.config().recording.fps;
    }

    private static String describe(StreamAbleClient client, VideoSettings.Output output) {
        Resolution canvas = client.canvasResolution();
        OutputTransform t = OutputTransform.compute(canvas, output.resolve(canvas), output.effectiveMode());
        String text = t.describe() + ".";
        if (t.hasBars()) {
            text += t.isPillarboxed() ? " Black bars at the sides." : " Black bars above and below.";
        }
        if (t.distorts()) {
            text += " The picture is distorted.";
        }
        return text + " " + output.effectiveMode().description();
    }

    private static List<OutputValidation.Issue> issues(Studio s, VideoSettings.Output output,
                                                       OutputValidation.Target target) {
        StreamAbleClient client = s.client();
        boolean streaming = target == OutputValidation.Target.STREAMING;
        VideoEncoder encoder = streaming
                ? client.encoderProbe().resolve(client.config().streaming.encoder, true)
                : client.recording().plannedEncoder(client.config().recording);
        List<OutputValidation.Issue> list = new ArrayList<>(OutputValidation.validate(
                output.resolve(client.canvasResolution()), fps(client, streaming), encoder, target));
        Resolution canvas = client.canvasResolution();
        OutputTransform t = OutputTransform.compute(canvas, output.resolve(canvas), output.effectiveMode());
        if (t.distorts()) {
            list.add(new OutputValidation.Issue(OutputValidation.Severity.WARNING,
                    "Stretch changes the picture's shape: the canvas is " + canvas.marketedRatio()
                            + " but this output is " + output.resolve(canvas).marketedRatio() + "."));
        }
        return list;
    }

    private static int severityColor(OutputValidation.Severity severity) {
        return switch (severity) {
            case ERROR -> Theme.DANGER;
            case WARNING -> Theme.WARNING;
            case INFO -> Theme.INFO;
        };
    }
}
