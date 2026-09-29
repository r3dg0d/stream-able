package dev.streamable.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LegacyRecordableImportTest {

    @TempDir
    Path dir;

    @Test
    void isAvailableOnlyWhenLegacyFileExistsAndNotYetImported() throws IOException {
        StreamAbleConfig config = new StreamAbleConfig();
        assertFalse(LegacyRecordableImport.isAvailable(dir, config));

        Files.writeString(dir.resolve(LegacyRecordableImport.LEGACY_CONFIG_FILE), "{}",
                StandardCharsets.UTF_8);
        assertTrue(LegacyRecordableImport.isAvailable(dir, config));

        config.legacyRecordableImported = true;
        assertFalse(LegacyRecordableImport.isAvailable(dir, config));
    }

    @Test
    void importsMappedFieldsAndIgnoresRetiredOverlayAndCarryOvers() throws IOException {
        String legacy = """
                {
                  "format": "mkv",
                  "fps": 45,
                  "enabled": true,
                  "outputDir": "my-recordings",
                  "captureAudio": true,
                  "audioBitrateKbps": 256,
                  "audioSampleRate": 48000,
                  "audioSyncOffsetMs": -40,
                  "maxFileSizeMB": 2048,
                  "replayBufferEnabled": true,
                  "replayBufferDurationSeconds": 90,
                  "audioSource": "both",
                  "showOverlay": true,
                  "overlayScale": 150,
                  "killMontages": true,
                  "deferredCapture": true,
                  "deferredCaptureFps": 12,
                  "deferredOutputFps": 120,
                  "deferredInterpolation": true
                }
                """;
        Files.writeString(dir.resolve(LegacyRecordableImport.LEGACY_CONFIG_FILE), legacy,
                StandardCharsets.UTF_8);

        StreamAbleConfig config = new StreamAbleConfig();
        LegacyRecordableImport.Result result = LegacyRecordableImport.importInto(dir, config);

        assertTrue(result.success(), result.message());
        assertTrue(result.importedFields() >= 10, "mapped fields should count: " + result.importedFields());
        assertTrue(config.legacyRecordableImported);

        RecordingSettings rec = config.recording;
        assertEquals(RecordingSettings.Container.MKV, rec.container);
        assertEquals(45, rec.fps);
        assertEquals("my-recordings", rec.outputDirectory);
        assertTrue(rec.captureGameAudio);
        assertTrue(rec.captureMicrophone, "audioSource both turns mic on");
        assertEquals(256, rec.audioBitrateKbps);
        assertEquals(48_000, rec.audioSampleRate);
        assertEquals(-40, rec.audioDelayMs);
        assertEquals(2048, rec.maxFileSizeMb);
        assertTrue(rec.replayBufferEnabled);
        assertEquals(90, rec.replayBufferSeconds);
        // Retirees must not leave sticky state — fields no longer exist; import
        // simply skips the keys (asserted by success + no exception).
    }

    @Test
    void skipMarksImportedSoPromptDoesNotReturn() {
        StreamAbleConfig config = new StreamAbleConfig();
        assertFalse(config.legacyRecordableImported);
        LegacyRecordableImport.skip(config);
        assertTrue(config.legacyRecordableImported);
        assertFalse(LegacyRecordableImport.isAvailable(dir, config));
    }

    @Test
    void missingLegacyFileReportsFailureWithoutMarkingImported() {
        StreamAbleConfig config = new StreamAbleConfig();
        LegacyRecordableImport.Result result = LegacyRecordableImport.importInto(dir, config);
        assertFalse(result.success());
        assertFalse(config.legacyRecordableImported);
        assertEquals(0, result.importedFields());
    }

    @Test
    void micOnlyAudioSourceDisablesGameAudio() throws IOException {
        Files.writeString(dir.resolve(LegacyRecordableImport.LEGACY_CONFIG_FILE),
                "{ \"audioSource\": \"mic\" }", StandardCharsets.UTF_8);
        StreamAbleConfig config = new StreamAbleConfig();
        assertTrue(LegacyRecordableImport.importInto(dir, config).success());
        assertTrue(config.recording.captureMicrophone);
        assertFalse(config.recording.captureGameAudio);
    }
}
