package dev.streamable.config;

import dev.streamable.video.Resolution;
import dev.streamable.video.ScalingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMigrationTest {

    @TempDir
    Path dir;

    @Test
    void schemaOneIsMigratedWithoutMovingSourcesOrStretching() throws Exception {
        String v1 = """
                {"schemaVersion": 1,
                 "recording": {"width": 3440, "height": 1440, "fps": 60},
                 "streaming": {"width": 1920, "height": 1080, "fps": 60,
                   "destinations": [{"id": "", "name": "Twitch", "platform": "TWITCH", "enabled": true,
                                     "ingestUrl": "rtmp://live.twitch.tv/app", "streamKey": "live_123_secret"}]},
                 "ui": {"canvasWidth": 3440, "canvasHeight": 1440},
                 "browserSources": [{"name": "Alerts", "url": "https://example.org", "x": 3000, "y": 100,
                                     "width": 400, "height": 300}]}
                """;
        Files.writeString(dir.resolve(ConfigIo.CONFIG_FILE_NAME), v1, StandardCharsets.UTF_8);
        StreamAbleConfig config = ConfigIo.load(dir);

        assertEquals(StreamAbleConfig.CURRENT_SCHEMA_VERSION, config.schemaVersion);
        assertEquals(new Resolution(3440, 1440), config.video.canvas());
        assertTrue(config.video.canvasInitialised, "migrated canvas must not be replaced by the window size");
        assertTrue(config.video.recording.matchCanvas, "recording == canvas becomes Native");
        assertFalse(config.video.streaming.matchCanvas);
        assertEquals(ScalingMode.FIT, config.video.streaming.mode, "schema 1 stretched; never migrate to Stretch");
        assertEquals(new Resolution(1920, 1080), config.video.streaming.resolve(config.video.canvas()));
        assertEquals(3000, config.browserSources.getFirst().x, 1e-9, "sources keep their canvas position");
        assertEquals("live_123_secret", config.streaming.destinations.getFirst().streamKey, "credentials preserved");
    }

    @Test
    void freshInstallAdoptsTheGameResolutionLater() {
        StreamAbleConfig fresh = ConfigIo.load(dir);
        assertFalse(fresh.video.canvasInitialised);
        assertTrue(fresh.video.recording.matchCanvas);
        assertEquals(ScalingMode.FIT, fresh.video.streaming.mode);
    }

    @Test
    void savedFileRoundTrips() {
        StreamAbleConfig config = ConfigIo.load(dir);
        config.video.setCanvas(new Resolution(5120, 1440));
        config.video.streaming.mode = ScalingMode.CENTER_CROP;
        ConfigIo.save(dir, config);
        StreamAbleConfig reloaded = ConfigIo.load(dir);
        assertEquals(new Resolution(5120, 1440), reloaded.video.canvas());
        assertEquals(ScalingMode.CENTER_CROP, reloaded.video.streaming.mode);
    }

    @Test
    void matchedOutputRoundsDownToEvenVisibly() {
        VideoSettings.Output output = new VideoSettings.Output();
        output.matchCanvas = true;
        assertEquals(new Resolution(3440, 1440), output.resolve(new Resolution(3441, 1441)));
        output.matchCanvas = false;
        output.width = 3441;
        output.height = 1441;
        assertEquals(new Resolution(3441, 1441), output.resolve(new Resolution(1920, 1080)),
                "user-entered sizes are never changed; validation reports them");
    }
}
