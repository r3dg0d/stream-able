package dev.streamable.ui.studio;

import dev.streamable.StreamAbleClient;
import dev.streamable.config.StreamingSettings;
import dev.streamable.ffmpeg.AudioCodec;
import dev.streamable.ffmpeg.RateControl;
import dev.streamable.ui.kit.Label;
import dev.streamable.ui.kit.Layouts;
import dev.streamable.ui.kit.Segmented;
import dev.streamable.ui.kit.Slider;
import dev.streamable.ui.kit.Theme;
import dev.streamable.ui.kit.Toggle;
import dev.streamable.ui.kit.Widgets;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Streaming: the live encoder, bitrate and reconnection behaviour. */
final class StreamingPage {

    private StreamingPage() {
    }

    static void build(Studio s, Layouts.Column page) {
        StreamAbleClient client = s.client();
        StreamingSettings st = client.config().streaming;

        page.add(new Widgets.Notice(() -> client.streaming().isLive()
                ? "Changes apply the next time you go live." : null, () -> Theme.INFO));
        Layouts.Row mode = page.add(new Layouts.Row(Theme.SPACE_4));
        mode.add(new Segmented(List.of("Simple", "Advanced"), () -> st.advancedMode ? 1 : 0, i -> {
            st.advancedMode = i == 1;
            s.changed();
            s.screen().refresh();
        }), 150);
        mode.add(Layouts.spacer(0), -1);

        Widgets.Card video = page.add(new Widgets.Card(Theme.SPACE_5));
        video.add(new Widgets.SectionHeader("Video", () -> client.streamingOutput().label() + " at " + st.fps
                + " FPS (set on the Video page)"));
        video.add(RecordingPage.encoderDropdown(s, () -> st.encoder, v -> st.encoder = v, true));
        video.add(new Label(() -> RecordingPage.encoderNote(client.encoderProbe(),
                client.encoderProbe().resolve(st.encoder, true))
                + " Live streams use H.264, the codec every ingest accepts.")
                .color(Theme.TEXT_MUTED).scale(Theme.TEXT_CAPTION).wrap());
        video.add(new Slider("Video bitrate", 500, 20_000, 100, () -> st.bitrateKbps, v -> {
            st.bitrateKbps = (int) Math.round(v);
            if (st.rateControl == RateControl.CBR) {
                st.maxBitrateKbps = st.bitrateKbps;
            }
            st.bufferSizeKbits = Math.max(st.bufferSizeKbits, st.bitrateKbps);
            s.changed();
        }).format(v -> String.format(Locale.ROOT, "%,d kbps", Math.round(v))).defaultValue(6000)
                .tooltip("Most services recommend 4,500-6,000 kbps for 1080p60 and allow up to about 8,000."));
        video.add(new Label(() -> client.streaming().bandwidthEstimate(st).describe())
                .color(Theme.TEXT_SECONDARY).scale(Theme.TEXT_CAPTION).wrap());

        Widgets.Card audio = page.add(new Widgets.Card(Theme.SPACE_5));
        audio.add(new Widgets.SectionHeader("Audio", () -> "Sources are chosen on the Recording and Audio pages."));
        List<AudioCodec> codecs = Arrays.stream(AudioCodec.values()).filter(AudioCodec::isStreamSafe).toList();
        Layouts.Grid audioGrid = audio.add(new Layouts.Grid(170, Theme.SPACE_5));
        audioGrid.add(new dev.streamable.ui.kit.Dropdown("Codec", () -> codecs.stream().map(AudioCodec::displayName).toList(),
                () -> codecs.indexOf(st.audioCodec), i -> {
            st.audioCodec = codecs.get(i);
            s.changed();
        }).placeholder(() -> st.audioCodec.displayName() + " (not accepted by RTMP)"));
        audioGrid.add(s.intField("Bitrate (kbps)", () -> st.audioBitrateKbps, v -> st.audioBitrateKbps = v, 64, 320));

        Widgets.Card reconnect = page.add(new Widgets.Card(Theme.SPACE_5));
        reconnect.add(new Widgets.SectionHeader("Connection", () -> "What happens when a destination drops"));
        reconnect.add(Toggle.of("Reconnect automatically", () -> st.reconnect, v -> {
            st.reconnect = v;
            s.changed();
        }).detail(() -> "Each destination retries on its own; the others stay live."));
        Layouts.Grid retry = reconnect.add(new Layouts.Grid(150, Theme.SPACE_5));
        retry.visibleWhen(() -> st.reconnect);
        retry.add(s.intField("First retry after (ms)", () -> (int) st.reconnectDelayMs, v -> st.reconnectDelayMs = v, 500, 120_000));
        retry.add(s.intField("Longest wait (ms)", () -> (int) st.maxReconnectDelayMs, v -> st.maxReconnectDelayMs = v, 1000, 600_000));
        retry.add(s.intField("Give up after attempts", () -> st.maxReconnectAttempts, v -> st.maxReconnectAttempts = v, 1, 1000));

        if (!st.advancedMode) {
            return;
        }
        Widgets.Card advanced = page.add(new Widgets.Card(Theme.SPACE_5));
        advanced.add(new Widgets.SectionHeader("Advanced encoding", () -> "Defaults suit almost every service."));
        advanced.add(new Segmented(List.of("CBR", "VBR"), () -> st.rateControl == RateControl.VBR ? 1 : 0, i -> {
            st.rateControl = i == 1 ? RateControl.VBR : RateControl.CBR;
            if (st.rateControl == RateControl.CBR) {
                st.maxBitrateKbps = st.bitrateKbps;
            }
            s.changed();
        }).tooltip("CBR keeps the bitrate steady, which live ingests expect. VBR can save bandwidth on static scenes."));
        Layouts.Grid grid = advanced.add(new Layouts.Grid(150, Theme.SPACE_5));
        grid.add(s.intField("Maximum bitrate (kbps)", () -> st.maxBitrateKbps, v -> st.maxBitrateKbps = v, 500, 200_000)
                .visibleWhen(() -> st.rateControl == RateControl.VBR));
        grid.add(s.intField("Buffer size (kbit)", () -> st.bufferSizeKbits, v -> st.bufferSizeKbits = v, 500, 400_000));
        grid.add(s.doubleField("Keyframe interval (s)", () -> st.keyframeSeconds, v -> st.keyframeSeconds = v, 0.5, 10)
                .tooltip("Most services require 2 seconds."));
        grid.add(s.intField("B-frames", () -> st.bFrames, v -> st.bFrames = v, 0, 4));
        grid.add(s.intField("Encoder queue (frames)", () -> st.frameQueueCapacity, v -> st.frameQueueCapacity = v, 10, 600)
                .tooltip("Frames buffered ahead of the encoder. Higher tolerates hiccups but adds memory use."));
        grid.add(new dev.streamable.ui.kit.TextField("Encoder preset", () -> st.preset, v -> {
            st.preset = v.strip();
            s.changed();
        }).placeholder("Default for the encoder").maxLength(32)
                .tooltip("Passed to FFmpeg as -preset, e.g. p5 for NVENC or veryfast for x264. Blank uses Stream-able's default for the encoder."));
    }
}
