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
