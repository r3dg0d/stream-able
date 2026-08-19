package dev.streamable.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.streamable.StreamAbleLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Imports an existing Record-able configuration into Stream-able.
 *
 * <p>Stream-able is a rebrand of Record-able, so an upgrading player should not
 * have to set everything up again. This reads {@code config/recordable.json}
 * and maps the settings that still have a meaning.</p>
 *
 * <p>Two deliberate non-actions:</p>
 * <ul>
 *   <li>The old config file is <b>never deleted or modified</b>. If the player
 *       goes back to Record-able it still works.</li>
 *   <li>Recordings are <b>never moved</b>. Stream-able keeps Record-able's
 *       default {@code recordings} folder, so existing videos simply appear in
 *       the new library.</li>
 * </ul>
 *
 * <p>Every field is read defensively: the legacy file is user-editable and may
 * come from any Record-able build, so a missing or wrongly typed value is
 * skipped rather than aborting the import.</p>
 */
public final class LegacyRecordableImport {

    public static final String LEGACY_CONFIG_FILE = "recordable.json";

    private LegacyRecordableImport() {
    }

    /** Whether a Record-able config exists and has not been imported yet. */
    public static boolean isAvailable(Path configDir, StreamAbleConfig config) {
        return !config.legacyRecordableImported
                && Files.isRegularFile(configDir.resolve(LEGACY_CONFIG_FILE));
    }

    /**
     * Applies the legacy settings to {@code config}.
     *
     * @return a short summary of what happened, for the UI and the log
     */
    public static Result importInto(Path configDir, StreamAbleConfig config) {
        Path legacy = configDir.resolve(LEGACY_CONFIG_FILE);
        if (!Files.isRegularFile(legacy)) {
            return new Result(false, "No Record-able configuration found.", 0);
        }
        try {
            String json = Files.readString(legacy, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return new Result(false, "Record-able configuration is not a JSON object.", 0);
            }
            JsonObject root = parsed.getAsJsonObject();
            RecordingSettings recording = config.recording;
            int imported = 0;

            imported += container(root, recording);
            imported += intField(root, "fps", v -> recording.fps = v);
            imported += boolField(root, "enabled", v -> recording.enabled = v);
            imported += stringField(root, "outputDir", v -> recording.outputDirectory = v);
            imported += boolField(root, "captureAudio", v -> recording.captureGameAudio = v);
            imported += intField(root, "audioBitrateKbps", v -> recording.audioBitrateKbps = v);
            imported += intField(root, "audioSampleRate", v -> recording.audioSampleRate = v);
            imported += intField(root, "audioSyncOffsetMs", v -> recording.audioDelayMs = v);
            imported += intField(root, "maxFileSizeMB", v -> recording.maxFileSizeMb = v);
            imported += boolField(root, "showOverlay", v -> recording.showRecordingOverlay = v);
            imported += boolField(root, "replayBufferEnabled", v -> recording.replayBufferEnabled = v);
            imported += intField(root, "replayBufferDurationSeconds", v -> recording.replayBufferSeconds = v);
            imported += intField(root, "overlayScale", v -> recording.overlayScale = v / 100.0f);
            // "audioSource" was game / mic / both in Record-able.
            imported += stringField(root, "audioSource", v -> {
                String mode = v.toLowerCase(Locale.ROOT);
                recording.captureMicrophone = mode.contains("mic") || mode.contains("both");
                recording.captureGameAudio = !mode.equals("mic");
            });

            config.legacyRecordableImported = true;
            config.validate();
            StreamAbleLog.CORE.info("Imported {} settings from the existing Record-able configuration.", imported);
            return new Result(true,
                    "Imported " + imported + " settings from Record-able. "
                            + "Your existing recordings folder is unchanged.", imported);
        } catch (IOException | RuntimeException e) {
            StreamAbleLog.CORE.warn("Could not import the Record-able configuration", e);
            return new Result(false, "Could not read the Record-able configuration: " + e.getMessage(), 0);
        }
    }

    /** Marks the import as declined so the prompt does not reappear. */
    public static void skip(StreamAbleConfig config) {
        config.legacyRecordableImported = true;
    }

    private static int container(JsonObject root, RecordingSettings recording) {
        return stringField(root, "format", value -> {
            for (RecordingSettings.Container candidate : RecordingSettings.Container.values()) {
                if (candidate.extension.equalsIgnoreCase(value)) {
                    recording.container = candidate;
                    return;
                }
            }
        });
    }

    private static int intField(JsonObject root, String name, java.util.function.IntConsumer setter) {
        JsonElement element = root.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return 0;
        }
        try {
            setter.accept(element.getAsInt());
            return 1;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int boolField(JsonObject root, String name, java.util.function.Consumer<Boolean> setter) {
        JsonElement element = root.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return 0;
        }
        try {
            setter.accept(element.getAsBoolean());
            return 1;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int stringField(JsonObject root, String name, java.util.function.Consumer<String> setter) {
        JsonElement element = root.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return 0;
        }
        try {
            String value = element.getAsString();
            if (value == null || value.isBlank()) {
                return 0;
            }
            setter.accept(value);
            return 1;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Outcome of an import attempt. */
    public record Result(boolean success, String message, int importedFields) {
    }
}
