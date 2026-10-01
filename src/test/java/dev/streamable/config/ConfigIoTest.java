package dev.streamable.config;

import dev.streamable.source.BrowserSource;
import dev.streamable.source.SourceList;
import dev.streamable.source.transform.SourceTransform;
import dev.streamable.streaming.StreamPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigIoTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "\"streaming\": null",
            "\"streaming\": {\"destinations\": null}",
            "\"streaming\": {\"destinations\": [null, {\"name\": \"Saved\", \"streamKey\": \"fixture_key\"}]}",
            "\"microphone\": {\"noise\": null}"
    })
    void nullSectionsAreRepairedBeforeMigrationWithoutDiscardingSettings(String section,
            @TempDir Path dir) throws IOException {
        Path file = dir.resolve(ConfigIo.CONFIG_FILE_NAME);
        Files.writeString(file, "{\"schemaVersion\": 1, \"recording\": {\"fps\": 24, "
                + "\"noiseSuppression\": true}, " + section + "}");
        StreamAbleConfig loaded = ConfigIo.load(dir);
        assertEquals(24, loaded.recording.fps, "repairable nulls must not reset unrelated settings");
        assertFalse(Files.exists(dir.resolve(ConfigIo.CONFIG_FILE_NAME + ".broken")));
        assertNotNull(loaded.streaming.destinations);
        assertNotNull(loaded.microphone.noise);
        if (section.contains("fixture_key")) {
            assertEquals(1, loaded.streaming.destinations.size());
            assertEquals("fixture_key", loaded.streaming.destinations.getFirst().streamKey);
        }
    }

    @Test
    void savesAndReloadsRoundTrip(@TempDir Path dir) {
        StreamAbleConfig config = new StreamAbleConfig();
        config.streaming.bitrateKbps = 9000;
        config.recording.fps = 30;
        config.ui.canvasWidth = 2560;

        SourceList sources = new SourceList();
        BrowserSource source = BrowserSource.create("Alerts", "https://example.invalid/overlay",
                1040, 40, 800, 600);
        source.setTransform(source.transform().withRotation(37.5));
        sources.add(source);
        config.captureSourceList(sources);

        ConfigIo.save(dir, config);
        StreamAbleConfig reloaded = ConfigIo.load(dir);

        assertEquals(9000, reloaded.streaming.bitrateKbps);
        assertEquals(30, reloaded.recording.fps);
        assertEquals(2560, reloaded.ui.canvasWidth);
        assertEquals(1, reloaded.browserSources.size());

        BrowserSource restored = reloaded.buildSourceList().snapshot().getFirst();
        assertEquals("Alerts", restored.name());
        assertEquals(source.id(), restored.id(), "the UUID must survive a round trip");
        assertEquals(37.5, restored.transform().rotation(), 1e-9);
        assertEquals(800, restored.transform().width(), 1e-9);
    }

    @Test
    void missingFileYieldsValidatedDefaults(@TempDir Path dir) {
        StreamAbleConfig config = ConfigIo.load(dir);
        assertEquals(StreamAbleConfig.CURRENT_SCHEMA_VERSION, config.schemaVersion);
        assertNotNull(config.recording);
        assertNotNull(config.streaming);
        assertTrue(config.browserSources.isEmpty());
    }

    @Test
    void unreadableFileIsQuarantinedRatherThanFatal(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(ConfigIo.CONFIG_FILE_NAME), "{ this is not json",
                StandardCharsets.UTF_8);
        StreamAbleConfig config = ConfigIo.load(dir);
        assertNotNull(config, "a broken config must never stop the game from starting");
        assertTrue(Files.exists(dir.resolve(ConfigIo.CONFIG_FILE_NAME + ".broken")));
    }

    @Test
    void malformedBrowserSourceIsRepairedNotFatal(@TempDir Path dir) throws IOException {
        String json = """
                {
                  "schemaVersion": 1,
                  "browserSources": [
                    { "id": "not-a-uuid", "name": "Bad", "url": "x",
                      "width": -50, "height": 0, "rotation": 1e9, "opacity": 42, "fps": 9999 }
                  ]
                }
                """;
        Files.writeString(dir.resolve(ConfigIo.CONFIG_FILE_NAME), json, StandardCharsets.UTF_8);

        StreamAbleConfig config = ConfigIo.load(dir);
        List<BrowserSource> sources = config.buildSourceList().snapshot();
        assertEquals(1, sources.size());
        BrowserSource source = sources.getFirst();
        assertNotNull(source.id(), "an unparseable id gets replaced, not rejected");
        assertTrue(source.transform().width() >= SourceTransform.MIN_SIZE);
        assertTrue(source.transform().height() >= SourceTransform.MIN_SIZE);
        assertTrue(source.transform().rotation() >= 0 && source.transform().rotation() < 360);
        assertEquals(1.0f, source.opacity(), 1e-6);
        assertTrue(source.browserFps() <= 240);
    }

    @Test
    void validationClampsHostileValues() {
        StreamAbleConfig config = new StreamAbleConfig();
        config.streaming.width = 1921;
        config.streaming.fps = 9999;
        config.streaming.bitrateKbps = -1;
        config.ui.streamHudPosition = 99;
        config.ui.snapThreshold = -5;
        config.validate();

        assertEquals(0, config.streaming.width % 2, "odd widths break yuv420p");
        assertTrue(config.streaming.fps <= 240);
        assertTrue(config.streaming.bitrateKbps >= 100);
        assertEquals(3, config.ui.streamHudPosition, "HUD corner clamps to 0–3");
        assertTrue(config.ui.snapThreshold >= 0);
    }

    @Test
    void validationRestoresFiniteHudDefaultsFromNaN() {
        StreamAbleConfig config = new StreamAbleConfig();
        config.ui.streamHudScale = Float.NaN;
        config.ui.streamHudOpacity = Float.NaN;
        config.ui.snapThreshold = Double.NaN;
        config.validate();

        assertEquals(1.0f, config.ui.streamHudScale, 1e-6);
        assertEquals(0.85f, config.ui.streamHudOpacity, 1e-6);
        assertEquals(8.0, config.ui.snapThreshold, 1e-9);
        assertTrue(Float.isFinite(config.ui.streamHudScale));
        assertTrue(Float.isFinite(config.ui.streamHudOpacity));
        assertTrue(Double.isFinite(config.ui.snapThreshold));
    }

    @Test
    void constantQualityIsRejectedForLiveOutput() {
        StreamAbleConfig config = new StreamAbleConfig();
        config.streaming.rateControl = dev.streamable.ffmpeg.RateControl.CONSTANT_QUALITY;
        config.validate();
        assertTrue(config.streaming.rateControl.isLiveSafe());
    }

    @Test
    void olderSchemaVersionsAreMigratedNotDiscarded(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(ConfigIo.CONFIG_FILE_NAME),
                "{ \"schemaVersion\": 0, \"recording\": { \"fps\": 24 } }", StandardCharsets.UTF_8);
        StreamAbleConfig config = ConfigIo.load(dir);
        assertEquals(StreamAbleConfig.CURRENT_SCHEMA_VERSION, config.schemaVersion);
        assertEquals(24, config.recording.fps, "existing settings must survive migration");
    }

    @Test
    void destinationsRoundTripIncludingPlatform(@TempDir Path dir) {
        StreamAbleConfig config = new StreamAbleConfig();
        StreamingSettings.Destination destination = new StreamingSettings.Destination();
        destination.id = java.util.UUID.randomUUID().toString();
        destination.name = "Twitch";
        destination.platform = StreamPlatform.TWITCH;
        destination.ingestUrl = "rtmp://live.twitch.tv/app";
        destination.streamKey = "live_1_secret";
        config.streaming.destinations.add(destination);

        ConfigIo.save(dir, config);
        StreamAbleConfig reloaded = ConfigIo.load(dir);
        assertEquals(1, reloaded.streaming.destinations.size());
        assertEquals(StreamPlatform.TWITCH, reloaded.streaming.destinations.getFirst().platform);
        assertTrue(reloaded.streaming.hasSecrets());
    }

    @Test
    void repairsTheBadKickIngestUrlFromAnEarlierBuild(@TempDir Path dir) throws IOException {
        // An earlier preset appended /app, which makes Kick's handshake fail.
        String json = """
                {
                  "schemaVersion": 1,
                  "streaming": { "destinations": [
                    { "name": "Kick", "platform": "KICK", "enabled": true,
                      "ingestUrl": "rtmps://abc123.global-contribute.live-video.net/app",
                      "streamKey": "sk_test" },
                    { "name": "Mine", "platform": "CUSTOM", "enabled": true,
                      "ingestUrl": "rtmp://my.server/app", "streamKey": "k" }
                  ] }
                }
                """;
        Files.writeString(dir.resolve(ConfigIo.CONFIG_FILE_NAME), json, StandardCharsets.UTF_8);

        StreamAbleConfig config = ConfigIo.load(dir);
        assertEquals("rtmps://abc123.global-contribute.live-video.net",
                config.streaming.destinations.getFirst().ingestUrl);
        assertEquals("rtmp://my.server/app", config.streaming.destinations.get(1).ingestUrl,
                "a deliberate custom /app path must not be touched");
    }

    @Test
    void aRemovedPlatformDegradesToCustomRatherThanBeingLost(@TempDir Path dir) throws IOException {
        // The Kick preset was removed; destinations saved with it must keep
        // working as ordinary custom RTMPS entries, credentials intact.
        String json = """
                {
                  "schemaVersion": 1,
                  "streaming": { "destinations": [
                    { "name": "Kick", "platform": "KICK", "enabled": true,
                      "ingestUrl": "rtmps://abc123.global-contribute.live-video.net/",
                      "streamKey": "sk_test" }
                  ] }
                }
                """;
        Files.writeString(dir.resolve(ConfigIo.CONFIG_FILE_NAME), json, StandardCharsets.UTF_8);

        StreamAbleConfig config = ConfigIo.load(dir);
        assertEquals(1, config.streaming.destinations.size(), "the destination must survive");
        StreamingSettings.Destination kick = config.streaming.destinations.getFirst();
        assertEquals(StreamPlatform.CUSTOM, kick.platform);
        assertEquals("Kick", kick.name, "the user's label is kept");
        assertEquals("sk_test", kick.streamKey, "credentials must not be lost");
        assertEquals("rtmps://abc123.global-contribute.live-video.net/", kick.ingestUrl);
    }

    @Test
    void saveDoesNotFollowAStaleTemporarySymlink(@TempDir Path dir) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(dir)
                .supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class));
        Path unrelated = dir.resolve("unrelated.txt");
        Files.writeString(unrelated, "keep this file");
        Path stale = dir.resolve(ConfigIo.CONFIG_FILE_NAME + ".tmp");
        Files.createSymbolicLink(stale, unrelated);
        StreamAbleConfig config = new StreamAbleConfig();
        config.recording.fps = 24;
        ConfigIo.save(dir, config);
        assertEquals("keep this file", Files.readString(unrelated));
        assertTrue(Files.isSymbolicLink(stale), "do not consume someone else's temporary path");
        assertEquals(24, ConfigIo.load(dir).recording.fps);
    }

    @Test
    void failedSaveRemovesOnlyItsOwnTemporaryFile(@TempDir Path dir) throws IOException {
        Path original = dir.resolve(ConfigIo.CONFIG_FILE_NAME);
        Files.createDirectory(original);
        Files.writeString(original.resolve("keep.txt"), "keep");
        ConfigIo.save(dir, new StreamAbleConfig());
        assertEquals("keep", Files.readString(original.resolve("keep.txt")));
        try (var entries = Files.list(dir)) {
            assertEquals(List.of(original), entries.toList(), "failed save must not leave a partial config");
        }
    }

    @Test
    void configWithSecretsIsNotWorldReadableOnPosix(@TempDir Path dir) throws IOException {
        StreamAbleConfig config = new StreamAbleConfig();
        StreamingSettings.Destination destination = new StreamingSettings.Destination();
        destination.streamKey = "live_1_secret";
        destination.ingestUrl = "rtmp://host/app";
        config.streaming.destinations.add(destination);
        ConfigIo.save(dir, config);

        Path file = dir.resolve(ConfigIo.CONFIG_FILE_NAME);
        if (Files.getFileStore(file).supportsFileAttributeView(
                java.nio.file.attribute.PosixFileAttributeView.class)) {
            var permissions = Files.getPosixFilePermissions(file);
            assertFalse(permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
            assertFalse(permissions.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ));
        }
    }

    @Test
    void repairsThePreviousYouTubePresetToRtmps(@TempDir Path dir) throws IOException {
        // Cycle 5 changed the YouTube preset to RTMPS; saved configs still hold
        // the old plain-RTMP default and should be promoted on load.
        String json = """
                {
                  "schemaVersion": 2,
                  "streaming": { "destinations": [
                    { "name": "YouTube", "platform": "YOUTUBE", "enabled": true,
                      "ingestUrl": "rtmp://a.rtmp.youtube.com/live2",
                      "streamKey": "abcd-efgh-ijkl-mnop" },
                    { "name": "YouTube slash", "platform": "YOUTUBE", "enabled": true,
                      "ingestUrl": "rtmp://a.rtmp.youtube.com/live2/",
                      "streamKey": "abcd-efgh-ijkl-mnop" },
                    { "name": "Deliberate RTMP", "platform": "YOUTUBE", "enabled": true,
                      "ingestUrl": "rtmp://a.rtmp.youtube.com/live2/custom-app",
                      "streamKey": "k" },
                    { "name": "Twitch", "platform": "TWITCH", "enabled": true,
                      "ingestUrl": "rtmp://live.twitch.tv/app", "streamKey": "live_1_x" }
                  ] }
                }
                """;
        Files.writeString(dir.resolve(ConfigIo.CONFIG_FILE_NAME), json, StandardCharsets.UTF_8);

        StreamAbleConfig config = ConfigIo.load(dir);
        String expected = StreamPlatform.YOUTUBE.defaultIngestUrl();
        assertTrue(expected.startsWith("rtmps://"), "preset must stay RTMPS");
        assertEquals(expected, config.streaming.destinations.get(0).ingestUrl);
        assertEquals(expected, config.streaming.destinations.get(1).ingestUrl);
        assertEquals("rtmp://a.rtmp.youtube.com/live2/custom-app",
                config.streaming.destinations.get(2).ingestUrl,
                "a non-preset path must not be rewritten");
        assertEquals("rtmp://live.twitch.tv/app",
                config.streaming.destinations.get(3).ingestUrl);
    }



    @Test
    void retiredKillMontagesAndDeferredCaptureKeysAreIgnoredAndDroppedOnSave(@TempDir Path dir)
            throws IOException {
        // Pre-1.3.3 (and Record-able) configs may still carry these never-wired fields.
        String json = """
                {
                  "schemaVersion": 2,
                  "recording": {
                    "fps": 60,
                    "killMontages": true,
                    "deferredCapture": true,
                    "deferredCaptureFps": 12,
                    "deferredOutputFps": 120,
                    "deferredInterpolation": true,
                    "showRecordingOverlay": false,
                    "overlayPosition": 2,
                    "overlayScale": 1.5
                  }
                }
                """;
        Files.writeString(dir.resolve(ConfigIo.CONFIG_FILE_NAME), json, StandardCharsets.UTF_8);

        StreamAbleConfig loaded = ConfigIo.load(dir);
        assertEquals(60, loaded.recording.fps, "known fields still load");
        ConfigIo.save(dir, loaded);

        String saved = Files.readString(dir.resolve(ConfigIo.CONFIG_FILE_NAME), StandardCharsets.UTF_8);
        for (String retired : new String[] {
                "killMontages", "deferredCapture", "deferredCaptureFps",
                "deferredOutputFps", "deferredInterpolation",
                "showRecordingOverlay", "overlayPosition", "overlayScale"
        }) {
            assertFalse(saved.contains("\"" + retired + "\""),
                    "retired key must not be rewritten: " + retired);
        }
    }

}
