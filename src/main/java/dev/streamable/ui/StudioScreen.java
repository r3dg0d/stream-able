package dev.streamable.ui;

import dev.streamable.StreamAbleClient;
import dev.streamable.browser.BrowserEngineStatus;
import dev.streamable.browser.audio.BrowserAudioBridge;
import dev.streamable.config.LegacyRecordableImport;
import dev.streamable.recording.RecordingController;
import dev.streamable.source.BrowserAudioMode;
import dev.streamable.source.BrowserSource;
import dev.streamable.source.OutputRouting;
import dev.streamable.streaming.BandwidthEstimator;
import dev.streamable.streaming.DestinationState;
import dev.streamable.streaming.StreamDestination;
import dev.streamable.streaming.StreamHealth;
import dev.streamable.streaming.StreamPlatform;
import dev.streamable.streaming.StreamingCredentials;
import dev.streamable.ui.widget.SecretEditBox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * Stream-able Studio: one screen for the whole mod.
 *
 * <p>Replaces the scattering of unrelated option screens with a single tabbed
 * surface, so the flow from "add a destination" to "add an overlay" to "go live"
 * happens in one place. The layout is deliberately Minecraft-native rather than
 * an imitation of OBS's chrome - OBS is the inspiration for the <em>workflow</em>,
 * not the look.</p>
 */
public final class StudioScreen extends Screen {

    private enum Section {
        HOME("Home"), RECORDING("Recording"), STREAMING("Streaming"), DESTINATIONS("Destinations"),
        SOURCES("Sources"), AUDIO("Audio"), ADVANCED("Advanced"), ABOUT("About");

        final String label;

        Section(String label) {
            this.label = label;
        }
    }

    private static final int NAV_WIDTH = 104;
    private static final int ROW_HEIGHT = 22;

    private final StreamAbleClient runtime;
    private final Screen parent;
    private Section section = Section.HOME;

    private UUID selectedDestination;
    private SecretEditBox streamKeyField;
    private EditBox ingestUrlField;
    private EditBox sourceUrlField;
    private String statusMessage = "";
    private int statusColour = 0xFFB0BEC5;

    public StudioScreen(StreamAbleClient runtime, Screen parent) {
        super(Component.translatable("screen.streamable.studio"));
        this.runtime = runtime;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int y = 32;
        for (Section candidate : Section.values()) {
            Button button = Button.builder(Component.literal(candidate.label), b -> {
                section = candidate;
                rebuildWidgets();
            }).bounds(8, y, NAV_WIDTH - 12, 20).build();
            button.active = candidate != section;
            addRenderableWidget(button);
            y += 22;
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(8, height - 28, NAV_WIDTH - 12, 20).build());

        switch (section) {
            case HOME -> buildHome();
            case RECORDING -> buildRecording();
            case STREAMING -> buildStreaming();
            case DESTINATIONS -> buildDestinations();
            case SOURCES -> buildSources();
            case AUDIO -> buildAudio();
            case ADVANCED -> buildAdvanced();
            case ABOUT -> buildAbout();
        }
    }

    private int contentX() {
        return NAV_WIDTH + 12;
    }

    private void setStatus(String message, boolean error) {
        statusMessage = message == null ? "" : message;
        statusColour = error ? 0xFFEF5350 : 0xFF81C784;
    }

    // ---- Home --------------------------------------------------------------

    private void buildHome() {
        int x = contentX();
        int y = height - 96;
        boolean recording = runtime.recording().isActive();
        boolean live = runtime.streaming().isLive();

        addRenderableWidget(Button.builder(
                Component.literal(recording ? "Stop Recording" : "Start Recording"), b -> {
                    if (recording) {
                        runtime.stopRecording();
                        setStatus("Recording saved.", false);
                    } else {
                        String error = runtime.startRecording();
                        setStatus(error == null ? "Recording started." : error, error != null);
                    }
                    rebuildWidgets();
                }).bounds(x, y, 150, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal(live ? "Stop Streaming" : "Start Streaming"), b -> {
                    if (live) {
                        runtime.stopStreaming();
                        setStatus("Stream stopped.", false);
                    } else {
                        String error = runtime.startStreaming();
                        setStatus(error == null ? "Stream started." : error, error != null);
                    }
                    rebuildWidgets();
                }).bounds(x + 158, y, 150, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Edit Sources"),
                        b -> minecraft.setScreen(new SourceEditorScreen(runtime)))
                .bounds(x, y + 24, 150, 20).build());

        if (LegacyRecordableImport.isAvailable(
                net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir(), runtime.config())) {
            addRenderableWidget(Button.builder(Component.literal("Import Record-able settings"), b -> {
                LegacyRecordableImport.Result result = LegacyRecordableImport.importInto(
                        net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir(), runtime.config());
                setStatus(result.message(), !result.success());
                runtime.saveNow();
                rebuildWidgets();
            }).bounds(x + 158, y + 24, 210, 20).build());
        }
    }

    private void renderHome(GuiGraphicsExtractor graphics) {
        int x = contentX();
        int y = 34;
        StreamHealth health = runtime.health();
        RecordingController.State recordingState = runtime.recording().state();

        graphics.text(font, "STREAM-ABLE", x, y, 0xFFFFFFFF);
        y += 16;
        y = line(graphics, x, y, "Recording", recordingState == RecordingController.State.IDLE
                ? "OFF" : recordingState.name());
        y = line(graphics, x, y, "Streaming", health.live() ? "LIVE" : "OFF");
        if (health.live()) {
            y = line(graphics, x, y, "Uptime", health.formattedUptime());
            y = line(graphics, x, y, "Encoder", health.encoderName());
            y = line(graphics, x, y, "Resolution",
                    runtime.config().streaming.width + "x" + runtime.config().streaming.height);
            y = line(graphics, x, y, "FPS", String.valueOf(health.fps()));
            y = line(graphics, x, y, "Video bitrate", health.videoBitrateKbps() + " kbps");
            y = line(graphics, x, y, "Dropped frames", String.valueOf(health.framesDropped()));
            if (health.isEncoderOverloaded()) {
                graphics.text(font, "Encoder overloaded - frame queue " + health.queuePressurePercent(),
                        x, y, 0xFFEF5350);
                y += 12;
            }
        }
        y += 6;
        graphics.text(font, "Destinations", x, y, 0xFFFFFFFF);
        y += 14;
        List<StreamDestination> destinations = runtime.streaming().destinations();
        if (destinations.isEmpty()) {
            graphics.text(font, "None configured - add one under Destinations.", x, y, 0xFF90A4AE);
        } else {
            for (StreamDestination destination : destinations) {
                graphics.text(font, "●", x, y, destination.state().colour());
                graphics.text(font, destination.name(), x + 12, y, 0xFFE0E0E0);
                graphics.text(font, destination.state().displayName(), x + 150, y,
                        destination.state().colour());
                y += 12;
            }
        }
    }

    private int line(GuiGraphicsExtractor graphics, int x, int y, String label, String value) {
        graphics.text(font, label + ":", x, y, 0xFF90A4AE);
        graphics.text(font, value, x + 130, y, 0xFFE0E0E0);
        return y + 12;
    }

    // ---- Destinations ------------------------------------------------------

    private void buildDestinations() {
        int x = contentX();
        int y = 32;
        List<StreamDestination> destinations = runtime.streaming().destinations();

        for (StreamDestination destination : destinations) {
            UUID id = destination.id();
            Button select = Button.builder(
                            Component.literal((destination.enabled() ? "[x] " : "[ ] ") + destination.name()),
                            b -> {
                                selectedDestination = id;
                                rebuildWidgets();
                            })
                    .bounds(x, y, 190, 20).build();
            select.active = !id.equals(selectedDestination);
            addRenderableWidget(select);

            addRenderableWidget(Button.builder(Component.literal(destination.enabled() ? "On" : "Off"), b -> {
                destination.setEnabled(!destination.enabled());
                runtime.markDirty();
                rebuildWidgets();
            }).bounds(x + 194, y, 34, 20).build());

            addRenderableWidget(Button.builder(Component.literal("X"), b -> {
                List<StreamDestination> updated = new java.util.ArrayList<>(destinations);
                updated.removeIf(d -> d.id().equals(id));
                runtime.streaming().setDestinations(updated);
                if (id.equals(selectedDestination)) {
                    selectedDestination = null;
                }
                runtime.markDirty();
                rebuildWidgets();
            }).bounds(x + 232, y, 22, 20).build());
            y += 22;
        }

        int addY = y + 4;
        int addX = x;
        for (StreamPlatform platform : StreamPlatform.values()) {
            addRenderableWidget(Button.builder(Component.literal("+ " + platform.displayName()), b -> {
                List<StreamDestination> updated = new java.util.ArrayList<>(destinations);
                StreamDestination created = StreamDestination.create(platform);
                updated.add(created);
                runtime.streaming().setDestinations(updated);
                selectedDestination = created.id();
                runtime.markDirty();
                rebuildWidgets();
            }).bounds(addX, addY, 96, 20).build());
            addX += 100;
            if (addX + 96 > width - 8) {
                addX = x;
                addY += 22;
            }
        }

        StreamDestination selected = findSelectedDestination();
        if (selected == null) {
            return;
        }
        int editorY = addY + 34;
        ingestUrlField = new EditBox(font, x, editorY + 10, width - x - 16, 18,
                Component.literal("Stream URL"));
        ingestUrlField.setMaxLength(512);
        ingestUrlField.setValue(selected.credentials().ingestUrl());
        ingestUrlField.setResponder(value -> {
            selected.setCredentials(new StreamingCredentials(value, selected.credentials().streamKey()));
            runtime.markDirty();
        });
        addRenderableWidget(ingestUrlField);

        streamKeyField = new SecretEditBox(font, x, editorY + 46, width - x - 96, 18,
                Component.literal("Stream Key"));
        streamKeyField.setSecret(selected.credentials().streamKey());
        streamKeyField.setResponder(value -> {
            selected.setCredentials(new StreamingCredentials(
                    selected.credentials().ingestUrl(), streamKeyField.secret()));
            runtime.markDirty();
        });
        addRenderableWidget(streamKeyField);

        addRenderableWidget(Button.builder(Component.literal("Show"), b -> {
            streamKeyField.toggleRevealed();
        }).bounds(width - 92, editorY + 46, 38, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Paste"), b -> {
            streamKeyField.setSecret(minecraft.keyboardHandler.getClipboard().trim());
            selected.setCredentials(new StreamingCredentials(
                    selected.credentials().ingestUrl(), streamKeyField.secret()));
            runtime.markDirty();
        }).bounds(width - 52, editorY + 46, 44, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Clear key"), b -> {
            streamKeyField.clearSecret();
            selected.setCredentials(new StreamingCredentials(selected.credentials().ingestUrl(), ""));
            runtime.markDirty();
        }).bounds(x, editorY + 70, 70, 18).build());

        if (runtime.streaming().isLive()) {
            addRenderableWidget(Button.builder(Component.literal("Reconnect"), b -> {
                boolean ok = runtime.streaming().reconnectDestination(selected.id());
                setStatus(ok ? "Reconnecting " + selected.name() : "Could not reconnect " + selected.name(), !ok);
            }).bounds(x + 76, editorY + 70, 80, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Copy diagnostic"), b -> {
                minecraft.keyboardHandler.setClipboard(runtime.streaming().diagnosticsFor(selected.id()));
                setStatus("Diagnostic copied (stream keys removed).", false);
            }).bounds(x + 162, editorY + 70, 118, 18).build());
        }
    }

    private void renderDestinations(GuiGraphicsExtractor graphics) {
        StreamDestination selected = findSelectedDestination();
        if (selected == null) {
            return;
        }
        int x = contentX();
        int y = height - 78;
        graphics.text(font, selected.platform().advice(), x, y, 0xFF90A4AE);
        String invalid = selected.validate();
        graphics.text(font, invalid == null ? "Ready" : invalid, x, y + 12,
                invalid == null ? 0xFF81C784 : 0xFFEF5350);
        graphics.text(font, "Publishing to: " + selected.credentials().redactedPublishUrl(),
                x, y + 24, 0xFF78909C);

        BandwidthEstimator.Estimate estimate = runtime.streaming().bandwidthEstimate(runtime.config().streaming);
        int estimateY = y - 52;
        for (String estimateLine : estimate.describe().split("\n")) {
            graphics.text(font, estimateLine, x, estimateY, 0xFFB0BEC5);
            estimateY += 10;
        }
    }

    private StreamDestination findSelectedDestination() {
        for (StreamDestination destination : runtime.streaming().destinations()) {
            if (destination.id().equals(selectedDestination)) {
                return destination;
            }
        }
        return null;
    }

    // ---- Sources -----------------------------------------------------------

    private void buildSources() {
        int x = contentX();
        int y = 32;
        List<BrowserSource> ordered = runtime.sources().displayOrder();

        for (BrowserSource source : ordered) {
            UUID id = source.id();
            String prefix = source.locked() ? "🔒 " : (source.visible() ? "👁 " : "   ");
            Button select = Button.builder(Component.literal(prefix + source.name()), b -> {
                runtime.editor().select(id);
                rebuildWidgets();
            }).bounds(x, y, 176, 20).build();
            select.active = !id.equals(runtime.editor().selectedId());
            addRenderableWidget(select);

            addRenderableWidget(Button.builder(Component.literal(source.visible() ? "Hide" : "Show"), b -> {
                source.setVisible(!source.visible());
                runtime.markDirty();
                rebuildWidgets();
            }).bounds(x + 180, y, 42, 20).build());
            addRenderableWidget(Button.builder(Component.literal(source.locked() ? "Unlock" : "Lock"), b -> {
                source.setLocked(!source.locked());
                runtime.markDirty();
                rebuildWidgets();
            }).bounds(x + 226, y, 52, 20).build());
            addRenderableWidget(Button.builder(Component.literal("↑"), b -> {
                runtime.sources().moveUp(id);
                runtime.markDirty();
                rebuildWidgets();
            }).bounds(x + 282, y, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal("↓"), b -> {
                runtime.sources().moveDown(id);
                runtime.markDirty();
                rebuildWidgets();
            }).bounds(x + 304, y, 20, 20).build());
            y += 22;
        }

        addRenderableWidget(Button.builder(Component.literal("+ Browser Source"), b -> {
            BrowserSource created = runtime.addBrowserSource("Browser Source", "about:blank");
            runtime.editor().select(created.id());
            rebuildWidgets();
        }).bounds(x, y + 4, 130, 20).build());

        BrowserSource selected = runtime.editor().selected();
        if (selected == null) {
            return;
        }
        addRenderableWidget(Button.builder(Component.literal("Duplicate"), b -> {
            runtime.duplicateSource(selected.id());
            rebuildWidgets();
        }).bounds(x + 134, y + 4, 76, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
            minecraft.setScreen(new ConfirmScreen(this,
                    "Delete '" + selected.name() + "'?",
                    "This removes the source and its settings.",
                    () -> {
                        runtime.removeSource(selected.id());
                        minecraft.setScreen(this);
                        rebuildWidgets();
                    }));
        }).bounds(x + 214, y + 4, 66, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b ->
                        runtime.browsers().refresh(selected.id(), true))
                .bounds(x + 284, y + 4, 66, 20).build());

        int propY = y + 32;
        sourceUrlField = new EditBox(font, x, propY + 10, width - x - 16, 18, Component.literal("URL"));
        sourceUrlField.setMaxLength(1024);
        sourceUrlField.setValue(selected.url());
        sourceUrlField.setResponder(value -> {
            selected.setUrl(value);
            runtime.markDirty();
        });
        addRenderableWidget(sourceUrlField);

        int fieldY = propY + 40;
        decimalField(x, fieldY, "Width", selected.transform().width(),
                v -> selected.setTransform(selected.transform().withSize(v, selected.transform().height())));
        decimalField(x + 92, fieldY, "Height", selected.transform().height(),
                v -> selected.setTransform(selected.transform().withSize(selected.transform().width(), v)));
        decimalField(x + 184, fieldY, "PosX", selected.transform().x(),
                v -> selected.setTransform(selected.transform().withPosition(v, selected.transform().y())));
        decimalField(x + 276, fieldY, "PosY", selected.transform().y(),
                v -> selected.setTransform(selected.transform().withPosition(selected.transform().x(), v)));
        decimalField(x + 368, fieldY, "Rot", selected.transform().rotation(),
                v -> selected.setTransform(selected.transform().withRotation(v)));

        int optY = fieldY + 30;
        addRenderableWidget(Button.builder(Component.literal("FPS: " + selected.browserFps()), b -> {
            int[] choices = BrowserSource.FPS_CHOICES;
            int index = 0;
            for (int i = 0; i < choices.length; i++) {
                if (choices[i] == selected.browserFps()) {
                    index = i;
                }
            }
            selected.setBrowserFps(choices[(index + 1) % choices.length]);
            runtime.markDirty();
            rebuildWidgets();
        }).bounds(x, optY, 76, 20).build());

        addRenderableWidget(Button.builder(Component.literal(routingLabel(selected.routing())), b -> {
            selected.setRouting(StreamAbleClient.nextRouting(selected.routing()));
            runtime.markDirty();
            rebuildWidgets();
        }).bounds(x + 80, optY, 190, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Transparent CSS preset"), b -> {
            selected.setCustomCss(BrowserSource.TRANSPARENT_CSS_PRESET);
            runtime.markDirty();
            setStatus("Transparency preset applied.", false);
        }).bounds(x + 274, optY, 160, 20).build());

        addRenderableWidget(Button.builder(
                        Component.literal("Audio: " + selected.audioMode().displayName()), b -> {
                            BrowserAudioMode[] modes = BrowserAudioMode.values();
                            selected.setAudioMode(modes[(selected.audioMode().ordinal() + 1) % modes.length]);
                            runtime.markDirty();
                            rebuildWidgets();
                        })
                .bounds(x, optY + 24, 190, 20).build());
    }

    private static String routingLabel(OutputRouting routing) {
        if (routing.equals(OutputRouting.ALL)) {
            return "Visible: screen + recording + stream";
        }
        if (routing.equals(OutputRouting.VIEWERS_ONLY)) {
            return "Visible: outputs only (hidden locally)";
        }
        if (routing.equals(OutputRouting.LOCAL_ONLY)) {
            return "Visible: my screen only";
        }
        return "Visible: custom";
    }

    private void renderSources(GuiGraphicsExtractor graphics) {
        int x = contentX();
        BrowserEngineStatus status = runtime.browsers().status();
        graphics.text(font, status.shortLabel(), x, height - 42,
                status.isReady() ? 0xFF81C784 : 0xFFFFB74D);
        if (!status.isReady()) {
            graphics.text(font, status.detail(), x, height - 30, 0xFF90A4AE);
        }
        BrowserSource selected = runtime.editor().selected();
        if (selected != null) {
            String limitation = BrowserAudioBridge.describeLimitation(selected);
            if (limitation != null) {
                graphics.textWithWordWrap(font, Component.literal(limitation),
                        x, height - 68, width - x - 16, 0xFFFFB74D);
            }
        }
    }

    // ---- Streaming / Recording / Audio / Advanced --------------------------

    private void buildStreaming() {
        var settings = runtime.config().streaming;
        int x = contentX();
        int y = 40;
        numberField(x, y, "Width", settings.width, v -> settings.width = v);
        numberField(x + 92, y, "Height", settings.height, v -> settings.height = v);
        numberField(x + 184, y, "FPS", settings.fps, v -> settings.fps = v);
        y += 30;
        numberField(x, y, "Bitrate kbps", settings.bitrateKbps, v -> settings.bitrateKbps = v);
        numberField(x + 92, y, "Max kbps", settings.maxBitrateKbps, v -> settings.maxBitrateKbps = v);
        numberField(x + 184, y, "Buffer kb", settings.bufferSizeKbits, v -> settings.bufferSizeKbits = v);
        y += 30;
        decimalField(x, y, "Keyframe s", settings.keyframeSeconds, v -> settings.keyframeSeconds = v);
        numberField(x + 92, y, "B-frames", settings.bFrames, v -> settings.bFrames = v);
        numberField(x + 184, y, "Audio kbps", settings.audioBitrateKbps, v -> settings.audioBitrateKbps = v);
        y += 30;
        addRenderableWidget(Button.builder(Component.literal(
                "Encoder: " + (settings.encoder.isBlank() ? "Auto-detect" : settings.encoder)), b -> {
            List<dev.streamable.ffmpeg.VideoEncoder> available =
                    runtime.encoderProbe().availableEncoders(true);
            if (available.isEmpty()) {
                setStatus("No usable H.264 encoder was found.", true);
                return;
            }
            int index = -1;
            for (int i = 0; i < available.size(); i++) {
                if (available.get(i).ffmpegName().equals(settings.encoder)) {
                    index = i;
                }
            }
            settings.encoder = index + 1 >= available.size() ? "" : available.get(index + 1).ffmpegName();
            runtime.markDirty();
            rebuildWidgets();
        }).bounds(x, y, 250, 20).build());
        addRenderableWidget(Button.builder(Component.literal(
                        "Reconnect: " + (settings.reconnect ? "On" : "Off")), b -> {
                    settings.reconnect = !settings.reconnect;
                    runtime.markDirty();
                    rebuildWidgets();
                })
                .bounds(x + 258, y, 130, 20).build());
        y += 24;
        numberField(x, y + 10, "Retry delay ms", (int) settings.reconnectDelayMs,
                v -> settings.reconnectDelayMs = v);
        numberField(x + 110, y + 10, "Max attempts", settings.maxReconnectAttempts,
                v -> settings.maxReconnectAttempts = v);
        numberField(x + 220, y + 10, "Queue depth", settings.frameQueueCapacity,
                v -> settings.frameQueueCapacity = v);
    }

    private void buildRecording() {
        var settings = runtime.config().recording;
        int x = contentX();
        int y = 40;
        numberField(x, y, "Width", settings.width, v -> settings.width = v);
        numberField(x + 92, y, "Height", settings.height, v -> settings.height = v);
        numberField(x + 184, y, "FPS", settings.fps, v -> settings.fps = v);
        y += 30;
        numberField(x, y, "Bitrate kbps", settings.bitrateKbps, v -> settings.bitrateKbps = v);
        numberField(x + 92, y, "Audio kbps", settings.audioBitrateKbps, v -> settings.audioBitrateKbps = v);
        numberField(x + 184, y, "Max size MB", (int) settings.maxFileSizeMb, v -> settings.maxFileSizeMb = v);
        y += 30;
        addRenderableWidget(Button.builder(Component.literal("Container: " + settings.container), b -> {
            var values = dev.streamable.config.RecordingSettings.Container.values();
            settings.container = values[(settings.container.ordinal() + 1) % values.length];
            runtime.markDirty();
            rebuildWidgets();
        }).bounds(x, y, 150, 20).build());
        toggle(x + 158, y, "Game audio", settings.captureGameAudio, v -> settings.captureGameAudio = v);
        toggle(x + 316, y, "Microphone", settings.captureMicrophone, v -> settings.captureMicrophone = v);
        y += 24;
        toggle(x, y, "Separate tracks", settings.separateAudioTracks, v -> settings.separateAudioTracks = v);
        toggle(x + 158, y, "Noise suppression", settings.noiseSuppression, v -> settings.noiseSuppression = v);
        toggle(x + 316, y, "Replay buffer", settings.replayBufferEnabled, v -> settings.replayBufferEnabled = v);
    }

    private void buildAudio() {
        int x = contentX();
        int y = 44;
        for (var bus : runtime.audioMixer().buses()) {
            addRenderableWidget(Button.builder(
                            Component.literal(bus.kind().displayName() + ": " + (bus.muted() ? "Muted" : "On")),
                            b -> {
                                bus.setMuted(!bus.muted());
                                rebuildWidgets();
                            })
                    .bounds(x, y, 200, 20).build());
            addRenderableWidget(Button.builder(
                            Component.literal("Vol " + Math.round(bus.volume() * 100) + "%"), b -> {
                                float next = bus.volume() >= 1.5f ? 0.25f : bus.volume() + 0.25f;
                                bus.setVolume(next);
                                rebuildWidgets();
                            })
                    .bounds(x + 206, y, 80, 20).build());
            y += 24;
        }

        y += 8;
        var recording = runtime.config().recording;
        toggle(x, y, "Capture voice chat", recording.captureVoiceChat, v -> {
            recording.captureVoiceChat = v;
            runtime.applyVoiceChatSettings();
        });
        toggle(x + 180, y, "Capture microphone", recording.captureMicrophone, v -> {
            recording.captureMicrophone = v;
            runtime.applyMicrophoneSettings();
        });
        y += 26;

        // ---- microphone device selection ----------------------------------
        List<dev.streamable.audio.JavaAudioCapture.AudioDeviceInfo> devices =
                runtime.microphone().devices().devices();
        String current = recording.microphoneDevice;
        String label = current.isBlank() ? "System default" : current;
        if (label.length() > 42) {
            label = label.substring(0, 39) + "...";
        }
        addRenderableWidget(Button.builder(
                        Component.literal("Microphone: " + label), b -> {
                            // Cycle through "system default" plus every input
                            // device, so no extra dropdown widget is needed.
                            List<String> choices = new java.util.ArrayList<>();
                            choices.add("");
                            for (var device : devices) {
                                choices.add(device.name());
                            }
                            int index = Math.max(0, choices.indexOf(recording.microphoneDevice));
                            recording.microphoneDevice = choices.get((index + 1) % choices.size());
                            runtime.markDirty();
                            runtime.restartMicrophone();
                            rebuildWidgets();
                        })
                .bounds(x, y, 330, 20).build());

        addRenderableWidget(Button.builder(
                        Component.literal("Gain " + recording.microphoneGainPercent + "%"), b -> {
                            int next = recording.microphoneGainPercent >= 200
                                    ? 25 : recording.microphoneGainPercent + 25;
                            recording.microphoneGainPercent = next;
                            runtime.markDirty();
                            runtime.applyMicrophoneSettings();
                            rebuildWidgets();
                        })
                .bounds(x + 336, y, 90, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(Component.literal("Test / restart microphone"), b -> {
            runtime.restartMicrophone();
            setStatus(runtime.microphone().status(), !runtime.microphone().isCapturing());
            rebuildWidgets();
        }).bounds(x, y, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Rescan devices"), b -> {
            setStatus(runtime.microphone().devices().devices().size()
                    + " input device(s) found.", false);
            rebuildWidgets();
        }).bounds(x + 206, y, 130, 20).build());
    }

    private void renderAudio(GuiGraphicsExtractor graphics) {
        int x = contentX();
        var mic = runtime.microphone();
        graphics.text(font, "Microphone: " + mic.status(), x, height - 68,
                mic.isCapturing() ? 0xFF81C784 : 0xFFFFB74D);
        if (!mic.isCapturing() && runtime.config().recording.captureMicrophone) {
            graphics.text(font,
                    "Capture starts with the recording or stream; use Test to check it now.",
                    x, height - 56, 0xFF90A4AE);
        }
        graphics.text(font, dev.streamable.compat.plasmovoice.PlasmoVoiceSupport.statusLine(),
                x, height - 44,
                dev.streamable.compat.plasmovoice.PlasmoVoiceSupport.isActive() ? 0xFF81C784 : 0xFF90A4AE);
        graphics.textWithWordWrap(font,
                Component.literal(dev.streamable.browser.audio.BrowserAudioBridge.limitationReason()),
                x, height - 32, width - x - 16, 0xFF78909C);
    }

    private void buildAdvanced() {
        var ui = runtime.config().ui;
        int x = contentX();
        int y = 40;
        numberField(x, y, "Canvas W", ui.canvasWidth, v -> {
            ui.canvasWidth = v;
            runtime.applyInterfaceSettings();
        });
        numberField(x + 92, y, "Canvas H", ui.canvasHeight, v -> {
            ui.canvasHeight = v;
            runtime.applyInterfaceSettings();
        });
        decimalField(x + 184, y, "Snap px", ui.snapThreshold, v -> {
            ui.snapThreshold = v;
            runtime.applyInterfaceSettings();
        });
        y += 30;
        toggle(x, y, "Snap to sources", ui.snapToOtherSources, v -> {
            ui.snapToOtherSources = v;
            runtime.applyInterfaceSettings();
        });
        toggle(x + 180, y, "Stream HUD", ui.showStreamHud, v -> ui.showStreamHud = v);
        toggle(x + 360, y, "Detailed HUD", ui.detailedStreamHud, v -> ui.detailedStreamHud = v);
        y += 24;
        toggle(x, y, "Premultiplied browser alpha", ui.premultipliedBrowserAlpha, v -> {
            ui.premultipliedBrowserAlpha = v;
            runtime.applyInterfaceSettings();
        });
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Re-probe encoders"), b -> {
            runtime.encoderProbe().invalidate();
            setStatus("Encoder list will be rebuilt on next use.", false);
        }).bounds(x, y, 170, 20).build());

        // Adds the bundled MCEF regression page as a source - the quickest way
        // to check transparency, clicks, typing and Backspace/Enter after an
        // MCEF or JCEF update.
        addRenderableWidget(Button.builder(Component.literal("Add browser test page"), b -> {
            String url = dev.streamable.browser.BrowserTestPage.extractAndGetUrl(
                    net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
            if (url == null) {
                setStatus("Could not write the test page.", true);
                return;
            }
            BrowserSource created = runtime.addBrowserSource("MCEF test page", url);
            runtime.editor().select(created.id());
            setStatus("Test page added as a browser source.", false);
        }).bounds(x + 178, y, 190, 20).build());
    }

    private void buildAbout() {
        int x = contentX();
        addRenderableWidget(Button.builder(Component.literal("Copy diagnostics"), b -> {
            minecraft.keyboardHandler.setClipboard(buildDiagnostics());
            setStatus("Diagnostics copied (no credentials included).", false);
        }).bounds(x, height - 56, 160, 20).build());
    }

    private String buildDiagnostics() {
        var resolution = runtime.ffmpeg().resolution();
        return String.join(System.lineSeparator(),
                "Stream-able diagnostics",
                "Minecraft: 26.1.2",
                "FFmpeg: " + (resolution.isAvailable() ? resolution.version() : "not found")
                        + " (" + resolution.describe() + ")",
                "Browser engine: " + runtime.browsers().status().shortLabel(),
                "Browser audio: " + BrowserAudioBridge.capability(),
                "Sources: " + runtime.sources().size(),
                "Destinations: " + runtime.streaming().destinations().size(),
                "Recording state: " + runtime.recording().state(),
                "Streaming state: " + runtime.streaming().state());
    }

    private void renderAbout(GuiGraphicsExtractor graphics) {
        int x = contentX();
        int y = 34;
        String[] lines = {
                "Stream-able - recording and livestreaming studio inside Minecraft",
                "",
                "Derived from Record-able by JoEusebe, MIT licensed.",
                "Browser sources are powered by MCEF Modern (LGPL-2.1) by DimasKama,",
                "which builds on JCEF and the Chromium Embedded Framework.",
                "",
                "Browser audio: " + BrowserAudioBridge.limitationReason()
        };
        for (String text : lines) {
            graphics.textWithWordWrap(font, Component.literal(text), x, y, width - x - 16, 0xFFB0BEC5);
            y += text.length() > 90 ? 22 : 12;
        }
    }

    // ---- widget helpers ----------------------------------------------------

    private void toggle(int x, int y, String label, boolean value, Consumer<Boolean> setter) {
        addRenderableWidget(Button.builder(
                        Component.literal(label + ": " + (value ? "On" : "Off")), b -> {
                            setter.accept(!value);
                            runtime.markDirty();
                            rebuildWidgets();
                        })
                .bounds(x, y, 152, 20).build());
    }

    private void numberField(int x, int y, String label, int value, IntConsumer setter) {
        decimalField(x, y, label, value, v -> setter.accept((int) Math.round(v)));
    }

    /**
     * A labelled numeric field.
     *
     * <p>Invalid text is simply ignored rather than reverting or throwing, so a
     * half-typed number does not fight the user mid-edit. The settings objects
     * clamp anything out of range when they are validated.</p>
     */
    private void decimalField(int x, int y, String label, double value, DoubleConsumer setter) {
        EditBox box = new EditBox(font, x, y + 10, 84, 18, Component.literal(label));
        box.setValue(formatNumber(value));
        box.setResponder(text -> {
            try {
                setter.accept(Double.parseDouble(text.trim()));
                runtime.markDirty();
            } catch (NumberFormatException ignored) {
                // Partial input while typing: keep the last valid value.
            }
        });
        addRenderableWidget(box);
        addRenderableOnly(new LabelRenderable(label, x, y));
    }

    private static String formatNumber(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.2f", value);
    }

    /** A tiny caption drawn above a field. */
    private final class LabelRenderable implements net.minecraft.client.gui.components.Renderable {
        private final String text;
        private final int x;
        private final int y;

        private LabelRenderable(String text, int x, int y) {
            this.text = text;
            this.x = x;
            this.y = y;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.text(font, text, x, y, 0xFF90A4AE);
        }
    }

    // ---- screen plumbing ---------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, NAV_WIDTH, height, 0xC0101418);
        graphics.fill(NAV_WIDTH, 0, width, height, 0xB0101418);
        graphics.text(font, "Stream-able Studio", 8, 12, 0xFFFFFFFF);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        switch (section) {
            case HOME -> renderHome(graphics);
            case DESTINATIONS -> renderDestinations(graphics);
            case SOURCES -> renderSources(graphics);
            case AUDIO -> renderAudio(graphics);
            case ABOUT -> renderAbout(graphics);
            default -> { }
        }
        if (!statusMessage.isEmpty()) {
            graphics.text(font, statusMessage, contentX(), height - 16, statusColour);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        runtime.saveNow();
        minecraft.setScreen(parent);
    }
}
