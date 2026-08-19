package dev.streamable.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import dev.streamable.StreamAbleLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Loading and saving of {@link StreamAbleConfig}.
 *
 * <p>Three properties matter here:</p>
 * <ul>
 *   <li><b>A broken file never blocks startup.</b> Unreadable JSON is moved
 *       aside and defaults are used, so a bad edit costs settings rather than a
 *       playable game.</li>
 *   <li><b>Writes are atomic.</b> The config is written to a temporary file and
 *       moved into place, so a crash mid-save cannot truncate it.</li>
 *   <li><b>Secrets get tight permissions.</b> When any destination holds a
 *       stream key the file is chmod 600 on POSIX systems.</li>
 * </ul>
 */
public final class ConfigIo {

    public static final String CONFIG_FILE_NAME = "stream-able.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private ConfigIo() {
    }

    /**
     * Reads the config, falling back to defaults on any problem.
     *
     * @param configDir the Fabric config directory
     */
    public static StreamAbleConfig load(Path configDir) {
        Path file = configDir.resolve(CONFIG_FILE_NAME);
        if (!Files.isRegularFile(file)) {
            StreamAbleConfig fresh = new StreamAbleConfig();
            fresh.validate();
            return fresh;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            StreamAbleConfig config = GSON.fromJson(json, StreamAbleConfig.class);
            if (config == null) {
                throw new JsonSyntaxException("Config file is empty");
            }
            migrate(config);
            config.validate();
            return config;
        } catch (IOException | RuntimeException e) {
            StreamAbleLog.CORE.error("Could not read {}; using defaults. The old file is kept as .broken",
                    file.getFileName(), e);
            quarantine(file);
            StreamAbleConfig fresh = new StreamAbleConfig();
            fresh.validate();
            return fresh;
        }
    }

    /**
     * Repairs Amazon IVS ingest URLs written by an earlier build.
     *
     * <p>A previous version's Kick preset appended {@code /app} on the
     * assumption that IVS required an application path. It does not - Kick
     * publishes to {@code rtmps://<host>/<key>} - and the extra segment makes
     * the handshake fail with nothing but FFmpeg's opaque "Input/output error".
     * Saved destinations keep the bad value, so it is corrected here.</p>
     *
     * <p>Keyed on the URL rather than the platform: the Kick preset has since
     * been removed, so those destinations now load as {@code CUSTOM}.</p>
     */
    private static void repairIvsIngestUrls(StreamAbleConfig config) {
        for (StreamingSettings.Destination destination : config.streaming.destinations) {
            String url = destination.ingestUrl;
            if (url == null) {
                continue;
            }
            String trimmed = url.trim();
            // Only the exact shape that build produced, so a deliberate custom
            // path on some other host is never touched.
            if (trimmed.toLowerCase(java.util.Locale.ROOT).contains("live-video.net")
                    && trimmed.endsWith("/app")) {
                destination.ingestUrl = trimmed.substring(0, trimmed.length() - "/app".length());
                StreamAbleLog.CORE.info(
                        "Removed the incorrect '/app' path from an Amazon IVS ingest URL; "
                                + "IVS publishes to rtmps://<host>/<key>.");
            }
        }
    }

    /** Applies schema migrations in order. Currently a no-op at version 1. */
    private static void migrate(StreamAbleConfig config) {
        repairIvsIngestUrls(config);
        if (config.schemaVersion < 1) {
            // Files written before the schema was versioned: nothing structural
            // changed, so validation alone brings them up to date.
            config.schemaVersion = 1;
        }
        if (config.schemaVersion > StreamAbleConfig.CURRENT_SCHEMA_VERSION) {
            StreamAbleLog.CORE.warn(
                    "Config schema version {} is newer than this build understands ({}); "
                            + "unknown settings will be preserved where possible.",
                    config.schemaVersion, StreamAbleConfig.CURRENT_SCHEMA_VERSION);
        }
    }

    private static void quarantine(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".broken"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            StreamAbleLog.CORE.warn("Could not set aside the unreadable config file", e);
        }
    }

    /** Writes the config atomically, tightening permissions when it holds secrets. */
    public static void save(Path configDir, StreamAbleConfig config) {
        Path file = configDir.resolve(CONFIG_FILE_NAME);
        Path temp = configDir.resolve(CONFIG_FILE_NAME + ".tmp");
        try {
            Files.createDirectories(configDir);
            config.validate();
            Files.writeString(temp, GSON.toJson(config), StandardCharsets.UTF_8);
            restrictPermissionsIfNeeded(temp, config);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissionsIfNeeded(file, config);
        } catch (IOException e) {
            StreamAbleLog.CORE.error("Failed to save the Stream-able config", e);
        }
    }

    /**
     * Restricts the config to owner-only when it contains a stream key.
     *
     * <p>Silently skipped on filesystems without POSIX permissions (Windows,
     * some network mounts); the security note in the README covers that case.</p>
     */
    private static void restrictPermissionsIfNeeded(Path file, StreamAbleConfig config) {
        if (!config.streaming.hasSecrets()) {
            return;
        }
        try {
            if (Files.getFileStore(file).supportsFileAttributeView(
                    java.nio.file.attribute.PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(file,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            StreamAbleLog.CORE.debug("Could not restrict config file permissions: {}", e.toString());
        }
    }
}
