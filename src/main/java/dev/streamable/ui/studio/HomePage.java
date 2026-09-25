package dev.streamable.ui.studio;

import dev.streamable.StreamAbleClient;
import dev.streamable.audio.AudioBus;
import dev.streamable.config.MicrophoneSettings;
import dev.streamable.recording.RecordingController;
import dev.streamable.streaming.StreamDestination;
import dev.streamable.streaming.StreamHealth;
import dev.streamable.ui.kit.Button;
import dev.streamable.ui.kit.IconButton;
import dev.streamable.ui.kit.Icons;
import dev.streamable.ui.kit.Label;
import dev.streamable.ui.kit.Layouts;
import dev.streamable.ui.kit.Painter;
import dev.streamable.ui.kit.Segmented;
import dev.streamable.ui.kit.Theme;
import dev.streamable.ui.kit.Toggle;
import dev.streamable.ui.kit.UiNode;
import dev.streamable.ui.kit.Widgets;

import java.util.List;
import java.util.Locale;

/** Home: the preview, both outputs, destinations at a glance and the mixer. */
final class HomePage {

    private HomePage() {
    }

    static void build(Studio s, Layouts.Column page) {
        StreamAbleClient client = s.client();
        Layouts.Adaptive split = page.add(new Layouts.Adaptive(520, Theme.SECTION_GAP));

        // ---- left: preview ---------------------------------------------------------------
        Layouts.Column left = split.add(new Layouts.Column(Theme.SPACE_4), 5);
        left.add(new PreviewNode(s, Math.max(120, s.screen().height - 150)));
        Layouts.Row guides = left.add(new Layouts.Row(Theme.SPACE_5));
        guides.add(Toggle.of("Safe-area guides", () -> client.config().video.showSafeAreaGuides, v -> {
            client.config().video.showSafeAreaGuides = v;
            s.changed();
        }).tooltip("Action-safe (93%) and title-safe (90%) guides. Shown only here, never in an output."), -1);
        guides.add(Toggle.of("Freeze game for outputs", () -> client.config().video.hideStudioFromOutputs, v -> {
            client.config().video.hideStudioFromOutputs = v;
            s.changed();
        }).tooltip("While a Stream-able screen is open, outputs keep showing the last game frame so "
                + "viewers never see these menus."), -1);
        left.add(new Label(() -> describeOutputs(client)).color(Theme.TEXT_MUTED).scale(Theme.TEXT_CAPTION).wrap());

        // ---- right: outputs, destinations, mixer ---------------------------------------------
        Layouts.Column right = split.add(new Layouts.Column(Theme.SECTION_GAP), 3);
        outputs(s, right.add(new Widgets.Card(Theme.SPACE_4)));
        destinations(s, right.add(new Widgets.Card(Theme.SPACE_4)));
        mixer(s, right.add(new Widgets.Card(Theme.SPACE_4)));
    }

    private static String describeOutputs(StreamAbleClient client) {
        var video = client.config().video;
        return "Recording " + client.recordingOutput().label() + " (" + video.recording.effectiveMode().displayName()
                + ")  ·  Stream " + client.streamingOutput().label() + " ("
                + video.streaming.effectiveMode().displayName() + ")  ·  Game on canvas: "
                + video.gameScaling.displayName();
    }

    private static void outputs(Studio s, Widgets.Card card) {
        StreamAbleClient client = s.client();
        card.add(new Widgets.Caption("Outputs"));

        Layouts.Row rec = card.add(new Layouts.Row(Theme.SPACE_3));
        rec.add(new Widgets.StatusPill(() -> switch (client.recording().state()) {
            case IDLE -> "Idle";
            case STARTING -> "Starting";
            case RECORDING -> "REC " + Studio.clock(client.recording().elapsedMillis());
            case PAUSED -> "Paused " + Studio.clock(client.recording().elapsedMillis());
            case STOPPING -> "Finishing";
        }, () -> client.recording().isActive() ? Theme.RECORDING : Theme.TEXT_MUTED,
                client.recording().state() == RecordingController.State.RECORDING), -1);
        rec.add(new IconButton(() -> client.recording().state() == RecordingController.State.PAUSED
                ? Icons.Icon.PLAY : Icons.Icon.PAUSE, "Pause or resume the recording", () -> {
            if (client.recording().state() == RecordingController.State.PAUSED) {
                client.recording().resume();
            } else {
                client.recording().pause();
            }
        }).visibleWhen(() -> client.recording().isActive()), 18);
        rec.add(new Button(() -> client.recording().isActive() ? "Stop" : "Record", s::toggleRecording)
                .icon(() -> client.recording().isActive() ? Icons.Icon.STOP : Icons.Icon.RECORD)
                .variant(() -> client.recording().isActive() ? Button.Variant.DANGER : Button.Variant.SECONDARY), 64);
        card.add(new Label(() -> recordingLine(client)).color(Theme.TEXT_SECONDARY).scale(Theme.TEXT_CAPTION));

        card.add(new Widgets.Divider());
        Layouts.Row live = card.add(new Layouts.Row(Theme.SPACE_3));
        live.add(new Widgets.StatusPill(() -> client.streaming().isLive()
                ? "LIVE " + client.health().formattedUptime()
                : client.streaming().state() == dev.streamable.streaming.StreamController.State.STARTING
                ? "Connecting" : "Offline",
                () -> client.streaming().isLive() ? Theme.LIVE : Theme.TEXT_MUTED, true), -1);
        live.add(new Button(() -> client.streaming().isLive() ? "End" : "Go Live", s::toggleStreaming)
                .icon(Icons.Icon.LIVE)
                .variant(() -> client.streaming().isLive() ? Button.Variant.DANGER : Button.Variant.LIVE), 64);
        card.add(new Label(() -> streamLine(client)).color(Theme.TEXT_SECONDARY).scale(Theme.TEXT_CAPTION));
        card.add(new Widgets.Notice(() -> {
            String error = client.recording().lastError();
            return client.recording().isActive() || error == null || error.isBlank() ? null : "Recording: " + error;
        }, () -> Theme.DANGER));
        card.add(new Widgets.Notice(() -> {
            String error = client.streaming().lastError();
            return client.streaming().isLive() || error == null || error.isBlank() ? null : "Stream: " + error;
        }, () -> Theme.DANGER));
    }

    private static String recordingLine(StreamAbleClient client) {
        var settings = client.config().recording;
        if (client.recording().isActive()) {
            return Studio.bytes(client.recording().currentFileSizeBytes()) + " written  ·  "
                    + client.recording().framesDropped() + " frames dropped";
        }
        String quality = settings.rateControl == dev.streamable.ffmpeg.RateControl.CONSTANT_QUALITY
                ? "quality " + settings.qualityPreset + "%" : settings.bitrateKbps + " kbps";
        return client.recordingOutput().label() + " · " + settings.fps + " FPS · "
                + settings.container.name() + " · " + quality;
    }

    private static String streamLine(StreamAbleClient client) {
        StreamHealth health = client.health();
        if (health.live()) {
            return String.format(Locale.ROOT, "%s kbps out · %s FPS · %d dropped",
                    health.outputKbps() < 0 ? "-" : Math.round(health.outputKbps()),
                    health.encodeFps() < 0 ? "-" : Studio.fixed(health.encodeFps(), 0), health.framesDropped());
        }
        var settings = client.config().streaming;
        return client.streamingOutput().label() + " · " + settings.fps + " FPS · " + settings.bitrateKbps + " kbps";
    }

    private static void destinations(Studio s, Widgets.Card card) {
        StreamAbleClient client = s.client();
        card.add(new Widgets.Caption("Destinations"));
        List<StreamDestination> list = client.streaming().destinations();
        if (list.isEmpty()) {
            card.add(new Label(() -> "No destinations yet.").color(Theme.TEXT_MUTED));
        }
        for (StreamDestination destination : list) {
            card.add(new DestinationRow(s, destination));
        }
        card.add(Button.of("Manage destinations", () -> s.screen().show(Page.DESTINATIONS))
                .variant(Button.Variant.GHOST).icon(Icons.Icon.DESTINATIONS));
    }

    /** Name, live state and an enable switch. */
    private static final class DestinationRow extends UiNode {
        private final StreamDestination destination;

        DestinationRow(Studio s, StreamDestination destination) {
            this.destination = destination;
            add(new Toggle(() -> "", destination::enabled, v -> {
                destination.setEnabled(v);
                s.changed();
            })).tooltip("Include this destination when going live.");
        }

        @Override
        public int preferredHeight(int availableWidth) {
            return Theme.CONTROL_HEIGHT;
        }

        @Override
        protected void layout() {
            children.getFirst().setBounds(x + width - 30, y, 30, Theme.CONTROL_HEIGHT);
        }

        @Override
        protected void renderSelf(Painter p) {
            int color = destination.state().colour() | 0xFF000000;
            p.circle(x + 4, y + height / 2f, 2.5f, color);
            String state = destination.enabled() ? destination.state().displayName() : "Off";
            int sw = p.textWidth(state, Theme.TEXT_CAPTION, Painter.Weight.REGULAR);
            p.textClipped(destination.name(), x + 11, y + 4, width - 50 - sw, Theme.TEXT, 1f, Painter.Weight.REGULAR);
            p.text(state, x + width - 36 - sw, y + 5, Theme.TEXT_MUTED, Theme.TEXT_CAPTION, Painter.Weight.REGULAR);
        }
    }

    private static void mixer(Studio s, Widgets.Card card) {
        StreamAbleClient client = s.client();
        card.add(new Widgets.Caption("Mixer"));
        for (AudioBus bus : client.audioMixer().buses()) {
            if (bus.kind() == AudioBus.Kind.VOICE_CHAT
                    && !StreamAbleClient.voiceChatInstalled()) {
                continue;
            }
            card.add(new MixerStrip(s, bus));
        }
        MicrophoneSettings mic = client.config().microphone;
        Layouts.Row quick = card.add(new Layouts.Row(Theme.SPACE_3));
        quick.add(Toggle.of("Mic", () -> client.config().recording.captureMicrophone, v -> {
            client.config().recording.captureMicrophone = v;
            client.applyMicrophoneSettings();
            s.screen().updateMicrophoneUser();
            s.changed();
        }).tooltip("Include your microphone in recordings and streams."), 56);
        quick.add(new Segmented(List.of("Off", "Light", "Bal.", "Strong"), () -> mic.noise.level.ordinal(), i -> {
            mic.noise.level = MicrophoneSettings.NoiseLevel.values()[i];
            s.micChanged();
        }).tooltip("AI noise cancellation strength (runs locally). Details on the Audio page."), -1);
    }
}
