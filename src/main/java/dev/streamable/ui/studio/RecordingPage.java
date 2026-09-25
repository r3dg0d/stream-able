package dev.streamable.ui.studio;

import dev.streamable.StreamAbleClient;
import dev.streamable.config.RecordingSettings;
import dev.streamable.ffmpeg.AudioCodec;
import dev.streamable.ffmpeg.FFmpegCapabilityProbe;
import dev.streamable.ffmpeg.RateControl;
import dev.streamable.ffmpeg.VideoEncoder;
import dev.streamable.ui.kit.Button;
import dev.streamable.ui.kit.Dropdown;
import dev.streamable.ui.kit.Icons;
import dev.streamable.ui.kit.Label;
import dev.streamable.ui.kit.Layouts;
import dev.streamable.ui.kit.Segmented;
import dev.streamable.ui.kit.Slider;
import dev.streamable.ui.kit.TextField;
import dev.streamable.ui.kit.Theme;
import dev.streamable.ui.kit.Toggle;
import dev.streamable.ui.kit.Widgets;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Recording: file format, encoder, quality, audio tracks and limits. */
final class RecordingPage {

    private RecordingPage() {
    }

    static void build(Studio s, Layouts.Column page) {
        StreamAbleClient client = s.client();
        RecordingSettings rec = client.config().recording;

        page.add(new Widgets.Notice(() -> client.recording().isActive()
                ? "Changes apply to the next recording." : null, () -> Theme.INFO));

        Widgets.Card file = page.add(new Widgets.Card(Theme.SPACE_5));
        file.add(new Widgets.SectionHeader("File", () -> "Saved to " + client.recording().outputDirectory(rec)));
        Layouts.Row folder = file.add(new Layouts.Row(Theme.SPACE_3));
        folder.add(new TextField("Folder", () -> rec.outputDirectory, v -> {
            rec.outputDirectory = v.strip();
            s.changed();
        }).commitOnBlur().placeholder("Default: .minecraft/stream-able/recordings"), -1);
        folder.add(Button.of("Open", () -> {
            Path dir = client.recording().outputDirectory(rec);
            try {
                Files.createDirectories(dir);
                Util.getPlatform().openPath(dir);
            } catch (Exception e) {
                s.error("Could not open " + dir + ".");
            }
        }).icon(Icons.Icon.SOURCES), 60).tooltip("Open the recordings folder.");
        Layouts.Grid fmt = file.add(new Layouts.Grid(170, Theme.SPACE_5));
        fmt.add(s.enumDropdown("Container", RecordingSettings.Container.values(), c -> switch (c) {
            case MP4 -> "MP4 (most compatible)";
            case MKV -> "MKV (survives crashes)";
            case MOV -> "MOV (editing)";
            case WEBM -> "WebM (VP9/AV1 + Opus)";
        }, () -> rec.container, v -> rec.container = v));
        fmt.add(s.intField("Stop at size (MB, 0 = no limit)", () -> (int) rec.maxFileSizeMb,
                v -> rec.maxFileSizeMb = v, 0, 1_000_000));
        file.add(new Widgets.Notice(() -> rec.container.problemWith(client.recording().plannedEncoder(rec), rec.audioCodec),
                () -> Theme.DANGER));
        file.add(new Label(() -> "Size, scaling and frame rate are on the Video page: "
                + client.recordingOutput().label() + " at " + rec.fps + " FPS.")
                .color(Theme.TEXT_MUTED).scale(Theme.TEXT_CAPTION));

        Widgets.Card video = page.add(new Widgets.Card(Theme.SPACE_5));
        video.add(new Widgets.SectionHeader("Encoder", () -> {
            VideoEncoder planned = client.recording().plannedEncoder(rec);
            return planned == null ? "No encoder available yet" : "Will use " + planned.displayName();
        }));
        video.add(encoderDropdown(s, () -> rec.encoder, v -> rec.encoder = v, false));
        video.add(new Label(() -> encoderNote(client.encoderProbe(), client.recording().plannedEncoder(rec)))
                .color(Theme.TEXT_MUTED).scale(Theme.TEXT_CAPTION).wrap());
        List<RateControl> modes = List.of(RateControl.CONSTANT_QUALITY, RateControl.VBR, RateControl.CBR);
        video.add(new Segmented(List.of("Constant quality", "Variable bitrate", "Constant bitrate"),
                () -> Math.max(0, modes.indexOf(rec.rateControl)), i -> {
            rec.rateControl = modes.get(i);
            s.changed();
        }).tooltip("Constant quality spends bits where the picture needs them and is best for recordings."));
        video.add(new Slider("Quality", 0, 100, 1, () -> rec.qualityPreset, v -> {
            rec.qualityPreset = (int) Math.round(v);
            s.changed();
        }).format(v -> qualityLabel((int) Math.round(v))).defaultValue(50)
                .visibleWhen(() -> rec.rateControl == RateControl.CONSTANT_QUALITY));
        video.add(s.intField("Bitrate (kbps)", () -> rec.bitrateKbps, v -> rec.bitrateKbps = v, 500, 500_000)
                .visibleWhen(() -> rec.rateControl != RateControl.CONSTANT_QUALITY));

        Widgets.Card audio = page.add(new Widgets.Card(Theme.SPACE_5));
        audio.add(new Widgets.SectionHeader("Audio", () -> "Sources apply to recordings and streams."));
        Layouts.Grid sources = audio.add(new Layouts.Grid(170, Theme.SPACE_5));
        sources.add(Toggle.of("Game sound", () -> rec.captureGameAudio, v -> {
            rec.captureGameAudio = v;
            s.changed();
        }));
        if (StreamAbleClient.voiceChatInstalled()) {
            sources.add(Toggle.of("Voice chat (other players)", () -> rec.captureVoiceChat, v -> {
                rec.captureVoiceChat = v;
                client.applyVoiceChatSettings();
                s.changed();
            }));
        }
        sources.add(Toggle.of("Microphone", () -> rec.captureMicrophone, v -> {
            rec.captureMicrophone = v;
            client.applyMicrophoneSettings();
            s.changed();
        }).detail(() -> "Processing is set up on the Audio page."));
        sources.add(Toggle.of("Microphone on its own track", () -> rec.separateAudioTracks, v -> {
            rec.separateAudioTracks = v;
            s.changed();
        }).detail(() -> "Adds a second, mic-only audio track for editing.").enabledWhen(() -> rec.captureMicrophone));
        Layouts.Grid codec = audio.add(new Layouts.Grid(170, Theme.SPACE_5));
        codec.add(s.enumDropdown("Codec", AudioCodec.values(), AudioCodec::displayName, () -> rec.audioCodec,
                v -> rec.audioCodec = v));
        codec.add(s.intField("Bitrate (kbps)", () -> rec.audioBitrateKbps, v -> rec.audioBitrateKbps = v, 64, 512)
                .visibleWhen(() -> rec.audioCodec == AudioCodec.AAC || rec.audioCodec == AudioCodec.OPUS));
        codec.add(s.intField("Sync offset (ms)", () -> rec.audioDelayMs, v -> rec.audioDelayMs = v, -1000, 1000)
                .tooltip("Shifts the audio later (positive) or earlier (negative) if your setup needs it. "
                        + "Timing is measured automatically; most setups need 0."));

        replayCard(s, page.add(new Widgets.Card(Theme.SPACE_5)));
    }

    private static void replayCard(Studio s, Widgets.Card card) {
        StreamAbleClient client = s.client();
        RecordingSettings rec = client.config().recording;
        var buffer = client.replayBuffer();
        card.add(new Widgets.SectionHeader("Replay buffer and clips", () -> buffer.isRunning()
                ? String.format(Locale.ROOT, "Running - %.0f of %d s buffered. Save with F12.",
                buffer.bufferedSeconds(), buffer.configuredSeconds())
                : "Keeps the last moments ready to save as a clip, without recording everything."));
        Layouts.Row actions = card.add(new Layouts.Row(Theme.SPACE_3));
        actions.add(new Button(() -> buffer.isRunning() ? "Stop buffer" : "Start buffer", () -> {
            if (buffer.isRunning()) {
                client.stopReplayBuffer();
            } else {
                String error = client.startReplayBuffer();
                if (error != null) {
                    s.error(error);
                }
            }
        }).icon(() -> buffer.isRunning() ? Icons.Icon.STOP : Icons.Icon.PLAY), 96);
        actions.add(Button.of("Save replay", () -> client.saveReplay("manual").whenComplete((f, e) ->
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    if (e == null) {
                        s.toast("Saved " + f.getFileName());
                    }
                }))).variant(Button.Variant.PRIMARY).icon(Icons.Icon.DOWN)
                .enabledWhen(() -> buffer.isRunning() && !buffer.isSaving()), 100);
        actions.add(Button.of("Open clips", () -> {
            Path dir = buffer.clipsDirectory(client.recording().outputDirectory(rec));
            try {
                Files.createDirectories(dir);
                Util.getPlatform().openPath(dir);
            } catch (Exception e) {
                s.error("Could not open " + dir + ".");
            }
        }).variant(Button.Variant.GHOST), 80);
        actions.add(Layouts.spacer(0), -1);
        card.add(new Widgets.Notice(() -> buffer.isRunning() || buffer.lastError().isBlank() ? null
                : buffer.lastError(), () -> Theme.DANGER));
        card.add(new Label(() -> buffer.lastClip() == null ? "" : "Last clip: " + buffer.lastClip().getFileName())
                .color(Theme.TEXT_MUTED).scale(Theme.TEXT_CAPTION)).visibleWhen(() -> buffer.lastClip() != null);

        card.add(Toggle.of("Start automatically in a world", () -> rec.replayBufferEnabled, v -> {
            rec.replayBufferEnabled = v;
            s.changed();
        }).detail(() -> "Runs whenever you are in a world; stops when you leave."));
        card.add(new Slider("Length", 10, 600, 5, () -> rec.replayBufferSeconds, v -> {
            rec.replayBufferSeconds = (int) Math.round(v);
            s.changed();
        }).format(v -> Studio.clock(Math.round(v) * 1000L)).defaultValue(60)
                .tooltip("How much a clip holds. Takes effect when the buffer next starts. Uses about "
                        + "200 kB of memory per second for audio, and disk space at your recording bitrate."));
        card.add(new Label(() -> "The buffer encodes with your recording settings (" + client.recordingOutput().label()
                + ", " + rec.fps + " FPS) in a separate encoder session; clips are saved as MP4 in the clips folder.")
                .color(Theme.TEXT_MUTED).scale(Theme.TEXT_CAPTION).wrap());

        card.add(new Widgets.Caption("Automatic clips (need the replay buffer)"));
        Layouts.Grid auto = card.add(new Layouts.Grid(170, Theme.SPACE_5));
        auto.add(Toggle.of("When I die", () -> rec.autoClipOnDeath, v -> {
            rec.autoClipOnDeath = v;
            s.changed();
        }));
        auto.add(Toggle.of("When I kill something", () -> rec.autoClipOnKill, v -> {
            rec.autoClipOnKill = v;
            s.changed();
        }).detail(() -> "Melee kills: the mob or player you hit dies within 5 s."));
        auto.add(Toggle.of("When I earn an advancement", () -> rec.autoClipOnAdvancement, v -> {
            rec.autoClipOnAdvancement = v;
            s.changed();
        }));
        auto.add(Toggle.of("When I change dimension", () -> rec.autoClipOnDimensionChange, v -> {
            rec.autoClipOnDimensionChange = v;
            s.changed();
        }));
        card.add(new Label(() -> "Clips are saved " + Math.round(dev.streamable.recording.replay.ClipTriggers.AFTERMATH_SECONDS)
                + " s after the moment so the aftermath is included; events close together share one clip.")
                .color(Theme.TEXT_MUTED).scale(Theme.TEXT_CAPTION).wrap());
    }

    static String qualityLabel(int value) {
        String word = value >= 85 ? "Near lossless" : value >= 65 ? "High" : value >= 40 ? "Balanced" : "Small files";
        return value + "% · " + word;
    }

    /** "Auto" plus every encoder the probe found usable, optionally stream-safe only. */
    static Dropdown encoderDropdown(Studio s, java.util.function.Supplier<String> get,
                                    java.util.function.Consumer<String> set, boolean streamSafeOnly) {
        FFmpegCapabilityProbe probe = s.client().encoderProbe();
        java.util.function.Supplier<List<VideoEncoder>> usable = () -> probe.availableEncoders(streamSafeOnly);
        return new Dropdown("Video encoder", () -> {
            List<String> names = new ArrayList<>();
            VideoEncoder best = probe.bestEncoder(streamSafeOnly);
            names.add("Auto" + (best == null ? "" : " (" + best.displayName() + ")"));
            for (VideoEncoder encoder : usable.get()) {
                names.add(encoder.displayName());
            }
            return names;
        }, () -> {
            String current = get.get();
            if (current == null || current.isBlank()) {
                return 0;
            }
            List<VideoEncoder> list = usable.get();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).ffmpegName().equals(current)) {
                    return i + 1;
                }
            }
            return -1;
        }, i -> {
            set.accept(i == 0 ? "" : usable.get().get(i - 1).ffmpegName());
            s.changed();
        }).placeholder(() -> {
            VideoEncoder configured = VideoEncoder.byFfmpegName(get.get());
            return (configured == null ? get.get() : configured.displayName()) + " (not available here)";
        }).tooltip("Only encoders that passed a real test encode on this computer are listed.");
    }

    static String encoderNote(FFmpegCapabilityProbe probe, VideoEncoder planned) {
        if (!probe.isComplete()) {
            return "Testing which encoders work on this computer...";
        }
        if (planned == null) {
            return "No working encoder was found. See Components for FFmpeg's status.";
        }
        FFmpegCapabilityProbe.Result result = probe.result(planned);
        String speed = result != null && result.framesPerSecond() > 0
                ? String.format(Locale.ROOT, " Test encode ran at %.0f FPS at 720p.", result.framesPerSecond()) : "";
        return (planned.isHardware() ? "Hardware encoding: the GPU does the work, so the game keeps its frame rate."
                : "Software encoding uses the CPU and can lower the game's frame rate at high resolutions.") + speed;
    }
}
