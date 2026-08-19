package dev.streamable.source;

import dev.streamable.source.transform.SourceTransform;

import java.util.Objects;
import java.util.UUID;

/**
 * A live Chromium page composited over the game.
 *
 * <p>This is the persisted, engine-independent description of a browser source.
 * It deliberately holds no reference to a {@code BrowserHandle}: the runtime
 * browser is owned by {@code BrowserSourceManager} and keyed by {@link #id()},
 * so a source can be edited, saved and reloaded while the browser engine is
 * still starting up, unavailable, or being recycled.</p>
 *
 * <p>The five properties the user edits numerically - {@code Width},
 * {@code Height}, {@code PosX}, {@code PosY}, {@code Rot} - are exactly the
 * fields of {@link SourceTransform}, so the properties panel and the drag
 * handles always agree.</p>
 */
public final class BrowserSource implements StreamSource {

    /**
     * The transparency preset offered in the UI. Applied on every page load so
     * widgets that ship an opaque body still composite correctly over the game.
     */
    public static final String TRANSPARENT_CSS_PRESET = """
            body {
                background-color: rgba(0, 0, 0, 0);
                margin: 0px auto;
                overflow: hidden;
            }
            """;

    /** Browser refresh rates offered in the UI. A static goal widget does not need 240 Hz. */
    public static final int[] FPS_CHOICES = {15, 30, 60};

    private final UUID id;
    private String name;
    private String url;
    private SourceTransform transform;
    private String customCss;

    private boolean visible = true;
    private boolean locked = false;
    private float opacity = 1.0f;
    private int browserFps = 30;
    private OutputRouting routing = OutputRouting.ALL;

    private boolean refreshOnActivate = false;
    /** Frees the native browser while hidden, at the cost of a reload when shown again. */
    private boolean shutdownWhenHidden = false;

    private BrowserAudioMode audioMode = BrowserAudioMode.MONITOR_ONLY;
    private float audioVolume = 1.0f;

    public BrowserSource(UUID id, String name, String url, SourceTransform transform) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = normaliseName(name);
        this.url = url == null ? "" : url.trim();
        this.transform = Objects.requireNonNull(transform, "transform");
        this.customCss = TRANSPARENT_CSS_PRESET;
    }

    /** Creates a new source with a fresh identity, centred on the canvas. */
    public static BrowserSource create(String name, String url, double x, double y, int width, int height) {
        return new BrowserSource(UUID.randomUUID(), name, url, SourceTransform.of(x, y, width, height));
    }

    private static String normaliseName(String value) {
        if (value == null || value.isBlank()) {
            return "Browser Source";
        }
        return value.strip();
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = normaliseName(name);
    }

    @Override
    public SourceKind kind() {
        return SourceKind.BROWSER;
    }

    @Override
    public SourceTransform transform() {
        return transform;
    }

    @Override
    public void setTransform(SourceTransform transform) {
        this.transform = Objects.requireNonNull(transform, "transform");
    }

    @Override
    public boolean visible() {
        return visible;
    }

    @Override
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    @Override
    public boolean locked() {
        return locked;
    }

    @Override
    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    @Override
    public float opacity() {
        return opacity;
    }

    @Override
    public void setOpacity(float opacity) {
        this.opacity = (float) Math.clamp(opacity, 0.0, 1.0);
    }

    @Override
    public OutputRouting routing() {
        return routing;
    }

    @Override
    public void setRouting(OutputRouting routing) {
        this.routing = Objects.requireNonNull(routing, "routing");
    }

    public String url() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url.trim();
    }

    public String customCss() {
        return customCss;
    }

    public void setCustomCss(String customCss) {
        this.customCss = customCss == null ? "" : customCss;
    }

    public int browserFps() {
        return browserFps;
    }

    public void setBrowserFps(int browserFps) {
        this.browserFps = Math.clamp(browserFps, 1, 240);
    }

    public boolean refreshOnActivate() {
        return refreshOnActivate;
    }

    public void setRefreshOnActivate(boolean value) {
        this.refreshOnActivate = value;
    }

    public boolean shutdownWhenHidden() {
        return shutdownWhenHidden;
    }

    public void setShutdownWhenHidden(boolean value) {
        this.shutdownWhenHidden = value;
    }

    public BrowserAudioMode audioMode() {
        return audioMode;
    }

    public void setAudioMode(BrowserAudioMode audioMode) {
        this.audioMode = audioMode == null ? BrowserAudioMode.OFF : audioMode;
    }

    public float audioVolume() {
        return audioVolume;
    }

    public void setAudioVolume(float audioVolume) {
        this.audioVolume = (float) Math.clamp(audioVolume, 0.0, 1.0);
    }

    /** The off-screen browser viewport size, derived from the transform. */
    public int viewportWidth() {
        return (int) Math.round(transform.width());
    }

    public int viewportHeight() {
        return (int) Math.round(transform.height());
    }

    /** Deep copy with a new identity, for the Duplicate action. */
    public BrowserSource duplicate() {
        BrowserSource copy = new BrowserSource(UUID.randomUUID(), name + " (copy)", url,
                transform.translated(24, 24));
        copy.customCss = customCss;
        copy.visible = visible;
        copy.locked = locked;
        copy.opacity = opacity;
        copy.browserFps = browserFps;
        copy.routing = routing;
        copy.refreshOnActivate = refreshOnActivate;
        copy.shutdownWhenHidden = shutdownWhenHidden;
        copy.audioMode = audioMode;
        copy.audioVolume = audioVolume;
        return copy;
    }

    @Override
    public String toString() {
        return "BrowserSource[" + name + ", " + viewportWidth() + "x" + viewportHeight() + "]";
    }
}
