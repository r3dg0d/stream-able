package dev.streamable;

import dev.streamable.audio.AudioMixer;
import dev.streamable.audio.GameAudioTap;
import dev.streamable.browser.BrowserSourceManager;
import dev.streamable.compositor.EditorOverlayRenderer;
import dev.streamable.compositor.ProgramCanvas;
import dev.streamable.compositor.ProgramCompositor;
import dev.streamable.config.ConfigIo;
import dev.streamable.config.StreamAbleConfig;
import dev.streamable.config.StreamingSettings;
import dev.streamable.ffmpeg.FFmpegCapabilityProbe;
import dev.streamable.ffmpeg.FFmpegManager;
import dev.streamable.recording.RecordingController;
import dev.streamable.source.BrowserSource;
import dev.streamable.source.OutputRouting;
import dev.streamable.source.SourceList;
import dev.streamable.source.transform.SourceEditor;
import dev.streamable.streaming.StreamController;
import dev.streamable.streaming.StreamDestination;
import dev.streamable.streaming.StreamHealth;
import dev.streamable.streaming.StreamPlatform;
import dev.streamable.streaming.StreamingCredentials;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The Stream-able runtime: one owner for every subsystem.
 *
 * <p>Holds the configuration, the source list, the browser engine, the
 * compositor and the two independent controllers, and defines the per-frame
 * order of operations.</p>
 *
 * <h2>Frame order</h2>
 * <p>Called at the tail of the game's render pass:</p>
 * <ol>
 *   <li>Compose the program frame off-screen from the <em>clean</em> game image
 *       plus the sources routed to outputs.</li>
 *   <li>Read it back and hand it to the recorder and the streamer.</li>
 *   <li>Only then draw locally visible sources - and the editor overlay - onto
 *       the screen.</li>
 * </ol>
 * <p>Doing the output pass first is what allows a source to appear on stream but
 * not on the player's monitor, and guarantees edit handles never reach viewers.</p>
 *
 * <h2>Threading</h2>
 * <p>{@link #onFrameRendered()} runs on the render thread and only performs GL
 * work plus non-blocking queue offers. {@link #onClientTick()} runs on the
 * client thread and owns lifecycle changes. Nothing here blocks on FFmpeg or on
 * the network.</p>
 */
public final class StreamAbleClient {

    private static StreamAbleClient instance;

    private final Path configDirectory;
    private final StreamAbleConfig config;
    private final SourceList sources;
    private final BrowserSourceManager browsers = new BrowserSourceManager();
    private final ProgramCompositor compositor = new ProgramCompositor();
    private final SourceEditor editor;
    private final AudioMixer audioMixer = new AudioMixer();
    private final dev.streamable.audio.MicrophoneCapture microphone =
            new dev.streamable.audio.MicrophoneCapture(audioMixer);
    private final FFmpegManager ffmpeg;
    private final FFmpegCapabilityProbe encoderProbe;
    private final RecordingController recording;
    private final StreamController streaming;

    /** Converts the game's variable render rate into the encoder's fixed rate. */
    private dev.streamable.pipeline.FramePacer framePacer;
    private java.io.OutputStream mixerTapConsumer;
    private final java.util.function.Consumer<byte[]> streamAudioSink = this::submitStreamAudio;
    private boolean configDirty;
    private long lastSaveMillis;

    private StreamAbleClient(Path gameDirectory, Path configDirectory) {
        this.configDirectory = configDirectory;
        this.config = ConfigIo.load(configDirectory);
        this.sources = config.buildSourceList();
        this.editor = new SourceEditor(sources);
        this.ffmpeg = new FFmpegManager(gameDirectory);
        FFmpegManager.initShared(gameDirectory);
        this.encoderProbe = new FFmpegCapabilityProbe(ffmpeg);
        this.recording = new RecordingController(ffmpeg, encoderProbe, gameDirectory);
        this.streaming = new StreamController(ffmpeg, encoderProbe);
        this.streaming.setDestinations(loadDestinations());
        applyInterfaceSettings();
    }

    public static synchronized StreamAbleClient create() {
        if (instance == null) {
            FabricLoader loader = FabricLoader.getInstance();
            instance = new StreamAbleClient(loader.getGameDir(), loader.getConfigDir());
        }
        return instance;
    }

    public static StreamAbleClient get() {
        return instance;
    }

    // ---- accessors ---------------------------------------------------------

    public StreamAbleConfig config() {
        return config;
    }

    public SourceList sources() {
        return sources;
    }

    public BrowserSourceManager browsers() {
        return browsers;
    }

    public SourceEditor editor() {
        return editor;
    }

    public RecordingController recording() {
        return recording;
    }

    public StreamController streaming() {
        return streaming;
    }

    public AudioMixer audioMixer() {
        return audioMixer;
    }

    public dev.streamable.audio.MicrophoneCapture microphone() {
        return microphone;
    }

    /**
     * Starts or stops microphone capture to match the current settings.
     *
     * <p>Plasmo Voice's microphone feed is disabled whenever our own capture is
     * running: both write to the same bus, so leaving both on would mix the
     * microphone into the program twice.</p>
     */
    public void applyMicrophoneSettings() {
        boolean wanted = config.recording.captureMicrophone
                && (recording.isActive() || streaming.isLive());
        if (wanted && !microphone.isRunning()) {
            microphone.start(config.recording.microphoneDevice, config.recording.microphoneGainPercent);
        } else if (!wanted && microphone.isRunning()) {
            microphone.stop();
        } else if (microphone.isRunning()) {
            microphone.setGainPercent(config.recording.microphoneGainPercent);
        }
        applyVoiceChatSettings();
    }

    /** Restarts capture so a device change takes effect immediately. */
    public void restartMicrophone() {
        boolean wasRunning = microphone.isRunning();
        microphone.stop();
        if (wasRunning || recording.isActive() || streaming.isLive()) {
            if (config.recording.captureMicrophone) {
                microphone.start(config.recording.microphoneDevice, config.recording.microphoneGainPercent);
            }
        }
    }

    public FFmpegManager ffmpeg() {
        return ffmpeg;
    }

    public FFmpegCapabilityProbe encoderProbe() {
        return encoderProbe;
    }

    public ProgramCompositor compositor() {
        return compositor;
    }

    public ProgramCanvas canvas() {
        return new ProgramCanvas(config.ui.canvasWidth, config.ui.canvasHeight);
    }

    // ---- lifecycle ---------------------------------------------------------

    public void initialise() {
        browsers.initialise();
        // Plasmo Voice plays through its own OpenAL context, so proximity chat
        // has to be captured through its API rather than the loopback device.
        // Registration is retried on tick because Plasmo Voice may initialise
        // after us - Fabric does not order client entrypoints.
        tryRegisterVoiceChat();
        dev.streamable.browser.audio.BrowserAudioBridge.logCapability();
        FFmpegManager.Resolution resolution = ffmpeg.resolution();
        StreamAbleLog.CORE.info("FFmpeg: {} ({})",
                resolution.isAvailable() ? resolution.version() : "not found", resolution.describe());
    }

    /**
     * Registers the Plasmo Voice integration if it is available, and pushes the
     * current capture preferences into it.
     */
    private void tryRegisterVoiceChat() {
        if (dev.streamable.compat.plasmovoice.PlasmoVoiceSupport.register(audioMixer)) {
            applyVoiceChatSettings();
        }
    }

    /** Keeps the voice integration in step with the audio settings. */
    public void applyVoiceChatSettings() {
        // Only let Plasmo Voice supply the microphone when we are not capturing
        // it ourselves, otherwise the same voice lands in the mix twice.
        boolean voiceSuppliesMicrophone = config.recording.captureMicrophone && !microphone.isRunning();
        dev.streamable.compat.plasmovoice.PlasmoVoiceSupport.configure(
                config.recording.captureVoiceChat, voiceSuppliesMicrophone);
    }

    /** Applies UI settings that other subsystems cache. */
    public void applyInterfaceSettings() {
        applyVoiceChatSettings();
        editor.setCanvas(config.ui.canvasWidth, config.ui.canvasHeight);
        editor.setSnapping(config.ui.snapThreshold, config.ui.snapToOtherSources);
        compositor.setPremultipliedBrowserAlpha(config.ui.premultipliedBrowserAlpha);
    }

    /** Marks the config for saving; writes are debounced to avoid disk churn. */
    public void markDirty() {
        configDirty = true;
    }

    public void saveNow() {
        config.captureSourceList(sources);
        config.streaming.destinations = saveDestinations();
        ConfigIo.save(configDirectory, config);
        configDirty = false;
        lastSaveMillis = System.currentTimeMillis();
    }

    public void onClientTick() {
        if (!dev.streamable.compat.plasmovoice.PlasmoVoiceSupport.isSettled()) {
            tryRegisterVoiceChat();
        }
        browsers.tick(sources);
        streaming.tick();
        if (configDirty && System.currentTimeMillis() - lastSaveMillis > 2_000) {
            saveNow();
        }
    }

    /**
     * Per-frame work, at the tail of the game render pass.
     *
     * <p>Every failure is contained: a compositor problem must not take the
     * game's render loop down with it.</p>
     */
    public void onFrameRendered() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null) {
            return;
        }
        boolean needsOutput = recording.isRecording() || streaming.isLive();
        boolean editing = client.screen instanceof dev.streamable.ui.SourceEditorScreen;
        List<BrowserSource> visible = sources.snapshot();
        if (!needsOutput && visible.isEmpty() && !editing) {
            return;   // nothing to do: stay entirely out of the render path
        }

        try {
            if (needsOutput && compositor.ensureTarget(canvas())) {
                // Only composite when the encoder is actually owed a frame:
                // compositing and reading back at 300 fps to feed a 60 fps
                // encoder wastes GPU time and breaks the output timeline.
                int due = pacer().framesDue(System.nanoTime());
                if (due > 0) {
                    Predicate<BrowserSource> include = recording.isRecording() && streaming.isLive()
                            ? source -> source.routing().anyOutput()
                            : (recording.isRecording()
                            ? source -> source.routing().includeInRecording()
                            : source -> source.routing().includeInStream());
                    compositor.composeProgramFrame(sources, browsers, include);
                    byte[] frame = compositor.readFrame();
                    if (frame != null) {
                        // Repeat when the game fell behind, so the frame count
                        // still matches elapsed time.
                        for (int i = 0; i < due; i++) {
                            recording.submitFrame(frame);
                            streaming.submitFrame(frame);
                        }
                    }
                }
            } else if (!needsOutput) {
                framePacer = null;   // restart the timeline for the next session
            }

            renderLocalOverlay(client, editing);
        } catch (RuntimeException e) {
            StreamAbleLog.COMPOSITOR.error("Frame composition failed; disabling the compositor "
                    + "for this session to keep the game playable", e);
        }
    }

    private void renderLocalOverlay(Minecraft client, boolean editing) {
        int framebufferWidth = client.getWindow().getWidth();
        int framebufferHeight = client.getWindow().getHeight();
        if (framebufferWidth <= 0 || framebufferHeight <= 0) {
            return;
        }
        if (!compositor.ensureTarget(canvas())) {
            return;
        }
        compositor.renderToScreen(sources, browsers, framebufferWidth, framebufferHeight);
        if (editing) {
            // Editor chrome is local-only: it is drawn after the output frame
            // has already been composed and submitted.
            EditorOverlayRenderer.render(compositor.quadRenderer(), editor,
                    compositor.screenMapping(framebufferWidth, framebufferHeight),
                    framebufferWidth, framebufferHeight);
        }
    }

    /** The pacer for the current output session, created on first use. */
    private dev.streamable.pipeline.FramePacer pacer() {
        if (framePacer == null) {
            int fps = streaming.isLive() ? config.streaming.fps : config.recording.fps;
            framePacer = new dev.streamable.pipeline.FramePacer(fps);
            framePacer.reset(System.nanoTime());
        }
        return framePacer;
    }

    // ---- source operations -------------------------------------------------

    public BrowserSource addBrowserSource(String name, String url) {
        ProgramCanvas canvas = canvas();
        BrowserSource source = BrowserSource.create(
                name, url,
                (canvas.width() - 800) / 2.0, (canvas.height() - 600) / 2.0, 800, 600);
        sources.add(source);
        markDirty();
        return source;
    }

    public void removeSource(UUID id) {
        browsers.destroy(id);
        sources.remove(id);
        if (id.equals(editor.selectedId())) {
            editor.clearSelection();
        }
        markDirty();
    }

    public BrowserSource duplicateSource(UUID id) {
        BrowserSource original = sources.byId(id).orElse(null);
        if (original == null) {
            return null;
        }
        BrowserSource copy = original.duplicate();
        sources.add(copy);
        markDirty();
        return copy;
    }

    // ---- streaming / recording control -------------------------------------

    /** Starts the broadcast, wiring the audio mixer into every encoder group. */
    public String startStreaming() {
        boolean withAudio = config.recording.captureGameAudio || config.recording.captureMicrophone;
        if (withAudio) {
            audioMixer.start();
            // Through the tap, so a recording running at the same time keeps
            // its own audio.
            mixerTapConsumer = audioMixer.gameAudioStream();
            GameAudioTap.getInstance().addConsumer(mixerTapConsumer);
            audioMixer.addSink(streamAudioSink);
        }
        ProgramCanvas canvas = canvas();
        String error = streaming.start(config.streaming, withAudio, canvas.width(), canvas.height());
        if (error != null && withAudio) {
            detachMixer();
        } else {
            applyMicrophoneSettings();
        }
        return error;
    }

    public void stopStreaming() {
        streaming.stop();
        if (!recording.isActive()) {
            detachMixer();
        }
        applyMicrophoneSettings();
    }

    /** Method reference target; defers reading the final field until it is set. */
    private void submitStreamAudio(byte[] pcm) {
        streaming.submitAudio(pcm);
    }

    /** Releases the mixer's hold on game audio without touching the recorder's. */
    private void detachMixer() {
        if (mixerTapConsumer != null) {
            GameAudioTap.getInstance().removeConsumer(mixerTapConsumer);
            mixerTapConsumer = null;
        }
        audioMixer.removeSink(streamAudioSink);
        audioMixer.stop();
    }

    public String startRecording() {
        ProgramCanvas canvas = canvas();
        // The mixer clocks the microphone bus, so it must be running even when
        // only a local recording is active.
        if (!audioMixer.isActive()) {
            audioMixer.start();
            mixerTapConsumer = audioMixer.gameAudioStream();
            GameAudioTap.getInstance().addConsumer(mixerTapConsumer);
        }
        String error = recording.start(config.recording, canvas.width(), canvas.height());
        applyMicrophoneSettings();
        return error;
    }

    public Path stopRecording() {
        Path file = recording.stop();
        applyMicrophoneSettings();
        if (!streaming.isLive()) {
            detachMixer();
        }
        return file;
    }

    public StreamHealth health() {
        return streaming.health();
    }

    // ---- destination persistence -------------------------------------------

    private List<StreamDestination> loadDestinations() {
        List<StreamDestination> loaded = new ArrayList<>();
        for (StreamingSettings.Destination saved : config.streaming.destinations) {
            UUID id;
            try {
                id = saved.id == null || saved.id.isBlank() ? UUID.randomUUID() : UUID.fromString(saved.id);
            } catch (IllegalArgumentException e) {
                id = UUID.randomUUID();
            }
            StreamDestination destination = new StreamDestination(id, saved.name,
                    saved.platform == null ? StreamPlatform.CUSTOM : saved.platform,
                    new StreamingCredentials(saved.ingestUrl, saved.streamKey));
            destination.setEnabled(saved.enabled);
            loaded.add(destination);
        }
        return loaded;
    }

    private List<StreamingSettings.Destination> saveDestinations() {
        List<StreamingSettings.Destination> saved = new ArrayList<>();
        for (StreamDestination destination : streaming.destinations()) {
            StreamingSettings.Destination entry = new StreamingSettings.Destination();
            entry.id = destination.id().toString();
            entry.name = destination.name();
            entry.platform = destination.platform();
            entry.enabled = destination.enabled();
            entry.ingestUrl = destination.credentials().ingestUrl();
            entry.streamKey = destination.credentials().streamKey();
            saved.add(entry);
        }
        return saved;
    }

    /** Releases every native resource. Called on client shutdown. */
    public void shutdown() {
        try {
            if (recording.isActive()) {
                recording.stop();
            }
            streaming.stop();
            microphone.stop();
            detachMixer();
            saveNow();
        } catch (RuntimeException e) {
            StreamAbleLog.CORE.error("Error during Stream-able shutdown", e);
        } finally {
            browsers.close();
            compositor.close();
        }
    }

    /** Convenience for the UI: routing presets shown as a cycle. */
    public static OutputRouting nextRouting(OutputRouting current) {
        if (current.equals(OutputRouting.ALL)) {
            return OutputRouting.VIEWERS_ONLY;
        }
        if (current.equals(OutputRouting.VIEWERS_ONLY)) {
            return OutputRouting.LOCAL_ONLY;
        }
        return OutputRouting.ALL;
    }
}
