package dev.streamable.config;

import dev.streamable.source.BrowserAudioMode;
import dev.streamable.source.BrowserSource;
import dev.streamable.source.OutputRouting;
import dev.streamable.source.transform.SourceTransform;

import java.util.UUID;

/**
 * Persisted form of a {@link BrowserSource}.
 *
 * <p>Kept separate from the runtime model so the on-disk schema can stay stable
 * while the model evolves, and so a malformed entry can be rejected during load
 * without any half-built object escaping into the source list.</p>
 */
public final class BrowserSourceSettings {

    public String id = "";
    public String name = "Browser Source";
    public String type = "browser";
    public String url = "";
    public double width = 800;
    public double height = 600;
    public double x = 100;
    public double y = 50;
    public double rotation = 0.0;
    public double opacity = 1.0;
    public boolean visible = true;
    public boolean locked = false;
    public int fps = 30;
    public String customCss = BrowserSource.TRANSPARENT_CSS_PRESET;

    public boolean showLocally = true;
    public boolean includeInRecording = true;
    public boolean includeInStream = true;
    public boolean refreshOnActivate = false;
    public boolean shutdownWhenHidden = false;

    public BrowserAudioMode audioMode = BrowserAudioMode.MONITOR_ONLY;
    public double audioVolume = 1.0;

    /** Snapshot of a live source for saving. */
    public static BrowserSourceSettings from(BrowserSource source) {
        BrowserSourceSettings settings = new BrowserSourceSettings();
        settings.id = source.id().toString();
        settings.name = source.name();
        settings.url = source.url();
        SourceTransform transform = source.transform();
        settings.x = transform.x();
        settings.y = transform.y();
        settings.width = transform.width();
        settings.height = transform.height();
        settings.rotation = transform.rotation();
        settings.opacity = source.opacity();
        settings.visible = source.visible();
        settings.locked = source.locked();
        settings.fps = source.browserFps();
        settings.customCss = source.customCss();
        settings.showLocally = source.routing().showLocally();
        settings.includeInRecording = source.routing().includeInRecording();
        settings.includeInStream = source.routing().includeInStream();
        settings.refreshOnActivate = source.refreshOnActivate();
        settings.shutdownWhenHidden = source.shutdownWhenHidden();
        settings.audioMode = source.audioMode();
        settings.audioVolume = source.audioVolume();
        return settings;
    }

    /**
     * Rebuilds the runtime source, repairing anything out of range.
     *
     * <p>A missing or unparseable id gets a fresh UUID rather than failing the
     * whole load: one corrupt entry must never stop Minecraft from starting.</p>
     */
    public BrowserSource toSource() {
        UUID uuid;
        try {
            uuid = id == null || id.isBlank() ? UUID.randomUUID() : UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            uuid = UUID.randomUUID();
        }
        // SourceTransform's canonical constructor clamps NaN, infinities and
        // out-of-range sizes, so hostile values cannot produce a broken source.
        BrowserSource source = new BrowserSource(uuid, name, url,
                new SourceTransform(x, y, width, height, rotation));
        source.setOpacity((float) opacity);
        source.setVisible(visible);
        source.setLocked(locked);
        source.setBrowserFps(fps);
        source.setCustomCss(customCss == null ? "" : customCss);
        source.setRouting(new OutputRouting(showLocally, includeInRecording, includeInStream));
        source.setRefreshOnActivate(refreshOnActivate);
        source.setShutdownWhenHidden(shutdownWhenHidden);
        source.setAudioMode(audioMode);
        source.setAudioVolume((float) audioVolume);
        return source;
    }
}
