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
import dev.streamable.ffmpeg.FFmpegRuntime;
import dev.streamable.pipeline.VideoPipeline;
import dev.streamable.recording.RecordingController;
import dev.streamable.runtime.RuntimeManager;
import dev.streamable.runtime.RuntimeState;
import dev.streamable.source.BrowserSource;
import dev.streamable.source.OutputRouting;
import dev.streamable.source.SourceList;
import dev.streamable.source.transform.SourceEditor;
import dev.streamable.streaming.StreamController;
import dev.streamable.streaming.StreamDestination;
import dev.streamable.streaming.StreamHealth;
import dev.streamable.streaming.StreamPlatform;
import dev.streamable.streaming.StreamingCredentials;
import dev.streamable.video.Resolution;
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
 * <p>Holds the configuration, the source list, the managed runtimes, the video
 * pipeline, the audio mixer and the two independent output controllers, and
 * defines the per-frame order of operations. The logic of each lives in its own
 * class; this one only wires them together.</p>
 *
 * <h2>Frame order</h2>
 * <p>Called at the tail of the game's render pass:</p>
 * <ol>
 *   <li>The video pipeline composes the program canvas and captures each
 *       output (recording and stream at their own sizes and rates).</li>
 *   <li>Only then are locally visible sources - and the editor overlay - drawn
 *       onto the screen, so edit handles never reach viewers.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * <p>{@link #onFrameRendered()} runs on the render thread and only performs GL
 * work plus non-blocking queue offers. Downloads, probing, model loading and
 * DSP run on their own named worker threads.</p>
 */
public final class StreamAbleClient {

    private static StreamAbleClient instance;

    private final Path gameDirectory;
    private final Path configDirectory;
    private final StreamAbleConfig config;
    private final SourceList sources;
    private final BrowserSourceManager browsers = new BrowserSourceManager();
    private final ProgramCompositor compositor = new ProgramCompositor();
    private final VideoPipeline video = new VideoPipeline(compositor);
    private final SourceEditor editor;
    private final AudioMixer audioMixer = new AudioMixer();
    private final dev.streamable.audio.mic.MicrophoneService microphone;
    private final RuntimeManager runtimes;
    private final FFmpegRuntime ffmpegRuntime;
    private final dev.streamable.browser.BrowserRuntime browserRuntime;
    private final FFmpegManager ffmpeg;
    private final FFmpegCapabilityProbe encoderProbe;
    private final RecordingController recording;
    private final StreamController streaming;

    private java.io.OutputStream mixerTapConsumer;
    private final java.util.function.Consumer<byte[]> streamAudioSink = this::submitStreamAudio;
    private boolean configDirty;
    private long lastSaveMillis;

    private StreamAbleClient(Path gameDirectory, Path configDirectory) {
        this.gameDirectory = gameDirectory;
        this.configDirectory = configDirectory;
        this.config = ConfigIo.load(configDirectory);
        this.sources = config.buildSourceList();
        this.editor = new SourceEditor(sources);

        this.runtimes = RuntimeManager.create(gameDirectory, modVersion());
        this.ffmpegRuntime = runtimes.register(new FFmpegRuntime(runtimes.context()));
        this.browserRuntime = runtimes.register(new dev.streamable.browser.BrowserRuntime(
                runtimes.context(), BrowserSourceManager.mcefJcefDirectory()));
        this.ffmpeg = new FFmpegManager(gameDirectory);
        this.ffmpeg.setManagedRuntime(ffmpegRuntime);
        this.ffmpeg.setConfiguredPath(config.runtime.ffmpegOverridePath);
        FFmpegManager.initShared(ffmpeg);
        this.encoderProbe = new FFmpegCapabilityProbe(ffmpeg);

        this.microphone = new dev.streamable.audio.mic.MicrophoneService(audioMixer, config.microphone, runtimes);
        this.recording = new RecordingController(ffmpeg, encoderProbe, gameDirectory, audioMixer);
        this.streaming = new StreamController(ffmpeg, encoderProbe);
        this.streaming.setDestinations(loadDestinations());
        applyInterfaceSettings();
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer(StreamAble.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
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

    public Path configDirectory() {
        return configDirectory;
    }

    public Path gameDirectory() {
        return gameDirectory;
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

    public dev.streamable.audio.mic.MicrophoneService microphone() {
        return microphone;
    }

    public RuntimeManager runtimes() {
        return runtimes;
    }

    public FFmpegRuntime ffmpegRuntime() {
        return ffmpegRuntime;
    }

    public dev.streamable.browser.BrowserRuntime browserRuntime() {
        return browserRuntime;
    }

    /** Install or retry the browser engine (Runtime page). */
    public void installBrowserEngine() {
        config.runtime.browserEngineEnabled = true;
        markDirty();
        browsers.retry(true, Minecraft.getInstance());
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

    public VideoPipeline video() {
        return video;
    }

    /** The program canvas every source transform is expressed in. */
    public ProgramCanvas canvas() {
        return new ProgramCanvas(config.video.canvas());
    }

    public Resolution canvasResolution() {
        return config.video.canvas();
    }

    /** The current Minecraft framebuffer size, or {@code null} before the window exists. */
    public static Resolution gameResolution() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null) {
            return null;
        }
        return Resolution.tryOf(client.getWindow().getWidth(), client.getWindow().getHeight());
    }

    public Resolution recordingOutput() {
        return config.video.recording.resolve(canvasResolution());
    }

    public Resolution streamingOutput() {
        return config.video.streaming.resolve(canvasResolution());
    }

    // ---- microphone ----------------------------------------------------------

    /**
     * Brings microphone capture in line with the outputs and settings.
     *
     * <p>With the system microphone selected, Plasmo Voice's microphone feed
     * is not captured - both would land on the same bus and the voice would be
     * mixed in twice. With the Plasmo Voice source selected, its processed
     * microphone is routed through Stream-able's chain instead.</p>
     */
    public void applyMicrophoneSettings() {
        microphone.setEnabled(config.recording.captureMicrophone);
        if (recording.isActive()) {
            microphone.acquire(dev.streamable.audio.mic.MicrophoneService.User.RECORDING);
        } else {
            microphone.release(dev.streamable.audio.mic.MicrophoneService.User.RECORDING);
        }
        if (streaming.isLive()) {
            microphone.acquire(dev.streamable.audio.mic.MicrophoneService.User.STREAMING);
        } else {
            microphone.release(dev.streamable.audio.mic.MicrophoneService.User.STREAMING);
        }
        applyVoiceChatSettings();
    }

    /** Restarts capture so a device change takes effect immediately. */
    public void restartMicrophone() {
        microphone.restartInput();
    }

    // ---- lifecycle ---------------------------------------------------------

    public void initialise() {
        runtimes.refreshAllAsync();
        browsers.initialise(browserRuntime, config.runtime.browserEngineEnabled, config.runtime.autoInstall,
                Minecraft.getInstance());
        // Plasmo Voice plays through its own OpenAL context, so proximity chat
        // has to be captured through its API rather than the loopback device.
        // Registration is retried on tick because Plasmo Voice may initialise
        // after us - Fabric does not order client entrypoints.
        tryRegisterVoiceChat();
        dev.streamable.browser.audio.BrowserAudioBridge.logCapability();
        initialiseFfmpegAsync();
    }

    /**
     * Resolves FFmpeg and, when the managed build is missing, installs it - all
     * on the runtime executor. Encoders are probed once a binary is known.
     */
    private void initialiseFfmpegAsync() {
        runtimes.context().executor().execute(() -> {
            ffmpegRuntime.refreshFromDisk();
            FFmpegManager.Resolution resolution = ffmpeg.refresh(config.runtime.ffmpegOverridePath);
            StreamAbleLog.CORE.info("FFmpeg: {} ({})",
                    resolution.isAvailable() ? resolution.version() : "not found yet", resolution.describe());
            boolean managedWanted = resolution.origin() != FFmpegManager.Origin.CONFIGURED
                    && resolution.origin() != FFmpegManager.Origin.MANAGED;
            if (managedWanted && config.runtime.autoInstall && ffmpegRuntime.artifact().isPresent()) {
                ffmpegRuntime.ensureReady().whenComplete((dir, error) -> onFfmpegChanged());
            } else if (resolution.origin() == FFmpegManager.Origin.MANAGED) {
                ffmpegRuntime.ensureReady().whenComplete((dir, error) -> onFfmpegChanged());
            } else {
                onFfmpegChanged();
            }
        });
    }

    /** Re-resolves the binary and re-probes encoders after an install or settings change. */
    public void onFfmpegChanged() {
        FFmpegManager.Resolution resolution = ffmpeg.refresh(config.runtime.ffmpegOverridePath);
        if (!config.runtime.allowSystemFfmpeg && resolution.origin() == FFmpegManager.Origin.SYSTEM_PATH
                && ffmpegRuntime.state() != RuntimeState.READY) {
            StreamAbleLog.FFMPEG.info("System FFmpeg on PATH ignored by settings.");
        }
        encoderProbe.invalidate();
        if (resolution.isAvailable()) {
            StreamAbleLog.CORE.info("Using FFmpeg: {} ({})", resolution.version(), resolution.describe());
            encoderProbe.probeAllAsync(runtimes.context().executor());
        }
    }

    /** Explicit install/retry from the Runtime page. */
    public void installFfmpeg() {
        ffmpegRuntime.ensureReady().whenComplete((dir, error) -> onFfmpegChanged());
    }

    private void tryRegisterVoiceChat() {
        if (dev.streamable.compat.plasmovoice.PlasmoVoiceSupport.register(audioMixer)) {
            applyVoiceChatSettings();
        }
    }

    /** Keeps the voice integration in step with the audio settings. */
    public void applyVoiceChatSettings() {
        // Plasmo Voice supplies the microphone only when it is the selected
        // source; otherwise the same voice would land in the mix twice.
        boolean voiceSuppliesMicrophone = config.recording.captureMicrophone
                && config.microphone.source == dev.streamable.config.MicrophoneSettings.Source.PLASMO_VOICE;
        dev.streamable.compat.plasmovoice.PlasmoVoiceSupport.configure(
                config.recording.captureVoiceChat, voiceSuppliesMicrophone);
    }

    /** Applies UI settings that other subsystems cache. */
    public void applyInterfaceSettings() {
        applyVoiceChatSettings();
        Resolution canvas = config.video.canvas();
        editor.setCanvas(canvas.width(), canvas.height());
        editor.setSnapping(config.ui.snapThreshold, config.ui.snapToOtherSources);
        compositor.setPremultipliedBrowserAlpha(config.ui.premultipliedBrowserAlpha);
    }

    /**
     * Changes the program canvas, optionally rescaling every source so the
     * layout keeps its relative placement. Refused while an output is active,
     * because the running encoders were sized from the old canvas.
     *
     * @return {@code null} on success, otherwise the reason
     */
    public String setCanvas(Resolution canvas, boolean rescaleSources) {
        if (recording.isActive() || streaming.isLive()) {
            return "Stop recording and streaming before changing the canvas.";
        }
        Resolution old = config.video.canvas();
        if (rescaleSources && !old.equals(canvas)) {
            double sx = canvas.width() / (double) old.width();
            double sy = canvas.height() / (double) old.height();
            double uniform = Math.min(sx, sy);
            for (BrowserSource source : sources.snapshot()) {
                var t = source.transform();
                // Centre positions scale per axis; sizes scale uniformly so
                // browser pages keep their shape.
                double cx = (t.x() + t.width() / 2.0) * sx;
                double cy = (t.y() + t.height() / 2.0) * sy;
                double w = t.width() * uniform;
                double h = t.height() * uniform;
                source.setTransform(new dev.streamable.source.transform.SourceTransform(
                        cx - w / 2.0, cy - h / 2.0, w, h, t.rotation()));
            }
        }
        config.video.setCanvas(canvas);
        applyInterfaceSettings();
        markDirty();
        return null;
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
        if (recording.tick()) {
            stopRecording();
        }
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
        adoptGameResolutionOnFirstRun();
        boolean editing = client.screen instanceof dev.streamable.ui.SourceEditorScreen;
        boolean ownScreen = client.screen instanceof dev.streamable.ui.StreamAbleScreen;
        List<BrowserSource> visible = sources.snapshot();
        if (!video.isActive() && visible.isEmpty() && !editing && !video.previewRequested(System.nanoTime())) {
            return;   // nothing to do: stay entirely out of the render path
        }
        try {
            boolean frozen = config.video.hideStudioFromOutputs && ownScreen && compositor.hasGameSnapshot();
            video.onFrame(sources, browsers, config.video.canvas(), config.video.gameScaling, frozen,
                    source -> source.routing().includeInRecording(),
                    source -> source.routing().includeInStream(),
                    routingsDiffer(visible));
            renderLocalOverlay(client, editing);
        } catch (RuntimeException e) {
            StreamAbleLog.COMPOSITOR.error("Frame composition failed; skipping this frame", e);
        }
    }

    private static boolean routingsDiffer(List<BrowserSource> sources) {
        for (BrowserSource source : sources) {
            if (source.visible() && source.routing().includeInRecording() != source.routing().includeInStream()) {
                return true;
            }
        }
        return false;
    }

    /** A brand-new install sizes its canvas to the game window once, then never changes it silently. */
    private void adoptGameResolutionOnFirstRun() {
        if (config.video.canvasInitialised) {
            return;
        }
        Resolution game = gameResolution();
        if (game == null) {
            return;
        }
        config.video.setCanvas(game);
        applyInterfaceSettings();
        markDirty();
        StreamAbleLog.COMPOSITOR.info("Program canvas initialised to the game resolution {} ({}).",
                game.label(), game.aspectClass().displayName());
    }

    private void renderLocalOverlay(Minecraft client, boolean editing) {
        int framebufferWidth = client.getWindow().getWidth();
        int framebufferHeight = client.getWindow().getHeight();
        if (framebufferWidth <= 0 || framebufferHeight <= 0) {
            return;
        }
        Resolution canvas = config.video.canvas();
        compositor.renderToScreen(sources, browsers, canvas, framebufferWidth, framebufferHeight);
        if (editing) {
            // Editor chrome is local-only: it is drawn after the output frame
            // has already been composed and submitted.
            EditorOverlayRenderer.render(compositor.quadRenderer(), editor,
                    new ProgramCanvas(canvas).mappingTo(framebufferWidth, framebufferHeight),
                    framebufferWidth, framebufferHeight);
        }
    }

    // ---- source operations -------------------------------------------------

    public BrowserSource addBrowserSource(String name, String url) {
        Resolution canvas = config.video.canvas();
        int width = (int) Math.min(800, canvas.width() * 0.5);
        int height = (int) Math.min(600, canvas.height() * 0.5);
        BrowserSource source = BrowserSource.create(name, url,
                (canvas.width() - width) / 2.0, (canvas.height() - height) / 2.0, width, height);
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

    private void attachMixer() {
        if (!audioMixer.isActive()) {
            audioMixer.start();
        }
        if (mixerTapConsumer == null) {
            // Through the tap, so a recording running at the same time keeps its own audio.
            mixerTapConsumer = audioMixer.gameAudioStream();
            GameAudioTap.getInstance().addConsumer(mixerTapConsumer);
        }
    }

    /** Releases the mixer once neither output needs it. */
    private void detachMixerIfIdle() {
        if (recording.isActive() || streaming.isLive()) {
            return;
        }
        if (mixerTapConsumer != null) {
            GameAudioTap.getInstance().removeConsumer(mixerTapConsumer);
            mixerTapConsumer = null;
        }
        audioMixer.stop();
    }

    /** Starts the broadcast, wiring the audio mixer into every encoder group. */
    public String startStreaming() {
        boolean withAudio = config.recording.captureGameAudio || config.recording.captureMicrophone
                || config.recording.captureVoiceChat;
        if (withAudio) {
            attachMixer();
            audioMixer.addSink(streamAudioSink);
        }
        Resolution output = streamingOutput();
        String error = streaming.start(config.streaming, withAudio, output);
        if (error != null) {
            audioMixer.removeSink(streamAudioSink);
            detachMixerIfIdle();
            return error;
        }
        video.startStreamingOutput(new VideoPipeline.OutputConfig("Streaming", output,
                config.video.streaming.effectiveMode(), config.streaming.fps, streaming::submitFrame));
        applyMicrophoneSettings();
        return null;
    }

    public void stopStreaming() {
        video.stopStreamingOutput();
        streaming.stop();
        audioMixer.removeSink(streamAudioSink);
        detachMixerIfIdle();
        applyMicrophoneSettings();
    }

    /** Method reference target; defers reading the final field until it is set. */
    private void submitStreamAudio(byte[] pcm) {
        streaming.submitAudio(pcm);
    }

    public String startRecording() {
        // The mixer clocks every audio bus, so it must run even for a local-only recording.
        attachMixer();
        Resolution output = recordingOutput();
        String error = recording.start(config.recording, output);
        if (error != null) {
            detachMixerIfIdle();
            return error;
        }
        video.startRecordingOutput(new VideoPipeline.OutputConfig("Recording", output,
                config.video.recording.effectiveMode(), config.recording.fps, recording::submitFrame));
        applyMicrophoneSettings();
        return null;
    }

    public Path stopRecording() {
        video.stopRecordingOutput();
        Path file = recording.stop();
        applyMicrophoneSettings();
        detachMixerIfIdle();
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
                stopRecording();
            }
            if (streaming.isLive()) {
                stopStreaming();
            }
            microphone.close();
            detachMixerIfIdle();
            saveNow();
        } catch (RuntimeException e) {
            StreamAbleLog.CORE.error("Error during Stream-able shutdown", e);
        } finally {
            browsers.close();
            runtimes.close();
        }
    }

    /** GL resources must be released on the render thread; called from CLIENT_STOPPING. */
    public void releaseGpuResources() {
        try {
            video.close();
            compositor.close();
        } catch (RuntimeException e) {
            StreamAbleLog.COMPOSITOR.debug("Error releasing GL resources", e);
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

    /** Filter helper retained for the UI's routing summaries. */
    public static Predicate<BrowserSource> anyOutput() {
        return source -> source.routing().anyOutput();
    }
}
