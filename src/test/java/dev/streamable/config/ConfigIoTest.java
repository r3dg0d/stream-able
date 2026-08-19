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
        config.recording.overlayScale = 99f;
        config.ui.snapThreshold = -5;
        config.validate();

        assertEquals(0, config.streaming.width % 2, "odd widths break yuv420p");
        assertTrue(config.streaming.fps <= 240);
        assertTrue(config.streaming.bitrateKbps >= 100);
        assertTrue(config.recording.overlayScale <= 2.0f);
        assertTrue(config.ui.snapThreshold >= 0);
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
}
