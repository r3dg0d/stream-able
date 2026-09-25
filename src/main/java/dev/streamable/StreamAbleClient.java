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
import dev.streamable.source.BrowserSource;
import dev.streamable.source.OutputRouting;
import dev.streamable.source.SourceList;
import dev.streamable.source.transform.SourceEditor;
import dev.streamable.streaming.StreamController;
import dev.streamable.streaming.StreamDestination;
import dev.streamable.ffmpeg.VideoEncoder;
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
    private final dev.streamable.recording.replay.ReplayBuffer replayBuffer;
    private final dev.streamable.recording.replay.ClipTriggers clipTriggers =
            new dev.streamable.recording.replay.ClipTriggers();
    private volatile boolean advancementEarned;
    private net.minecraft.world.entity.LivingEntity lastAttacked;
    private long lastAttackNanos;
    private boolean wasInWorld;
    private final dev.streamable.streaming.test.StreamTestController streamTests =
            new dev.streamable.streaming.test.StreamTestController();
    private dev.streamable.diagnostics.HealthReport cachedHealth;
    private long cachedHealthAt;
    private long cachedFreeDisk = -1;
    private long cachedFreeDiskAt;

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
        this.ffmpeg = new FFmpegManager();
        this.ffmpeg.setManagedRuntime(ffmpegRuntime);
        this.ffmpeg.setConfiguredPath(config.runtime.ffmpegOverridePath);
        this.ffmpeg.setAllowSystemPath(config.runtime.allowSystemFfmpeg);
        FFmpegManager.initShared(ffmpeg);
        this.encoderProbe = new FFmpegCapabilityProbe(ffmpeg);

        this.microphone = new dev.streamable.audio.mic.MicrophoneService(audioMixer, config.microphone, runtimes);
        this.recording = new RecordingController(ffmpeg, encoderProbe, gameDirectory, audioMixer);
        this.streaming = new StreamController(ffmpeg, encoderProbe);
        this.replayBuffer = new dev.streamable.recording.replay.ReplayBuffer(ffmpeg, encoderProbe, gameDirectory,
                audioMixer);
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

    public dev.streamable.recording.replay.ReplayBuffer replayBuffer() {
        return replayBuffer;
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
        if (replayBuffer.isRunning()) {
            microphone.acquire(dev.streamable.audio.mic.MicrophoneService.User.REPLAY_BUFFER);
        } else {
            microphone.release(dev.streamable.audio.mic.MicrophoneService.User.REPLAY_BUFFER);
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
        dev.streamable.compat.voicechat.SimpleVoiceChatSupport.attach(audioMixer);
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
        ffmpeg.setAllowSystemPath(config.runtime.allowSystemFfmpeg);
        FFmpegManager.Resolution resolution = ffmpeg.refresh(config.runtime.ffmpegOverridePath);
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

    /** Keeps the voice-chat integrations in step with the audio settings. */
    public void applyVoiceChatSettings() {
        // A voice-chat mod supplies the microphone only when it is the selected
        // source; otherwise the same voice would land in the mix twice.
        var source = config.recording.captureMicrophone ? microphone.effectiveSource()
                : dev.streamable.config.MicrophoneSettings.Source.SYSTEM;
        dev.streamable.compat.plasmovoice.PlasmoVoiceSupport.configure(config.recording.captureVoiceChat,
                source == dev.streamable.config.MicrophoneSettings.Source.PLASMO_VOICE);
        dev.streamable.compat.voicechat.SimpleVoiceChatSupport.configure(config.recording.captureVoiceChat,
                source == dev.streamable.config.MicrophoneSettings.Source.SIMPLE_VOICE_CHAT);
    }

    /** Whether any supported voice-chat mod is installed. */
    public static boolean voiceChatInstalled() {
        return dev.streamable.compat.plasmovoice.PlasmoVoiceSupport.isInstalled()
                || dev.streamable.compat.voicechat.SimpleVoiceChatSupport.isInstalled();
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

    /**
     * Freezes the current game picture for the outputs before a Stream-able
     * screen is drawn. Called when such a screen is opened: the main
     * framebuffer still holds the last frame drawn without it, so outputs and
     * the Studio preview never show Stream-able's own menus. Skipped when the
     * previous screen was also Stream-able's (its frame contains that screen).
     */
    public void freezeGameForStudio(net.minecraft.client.gui.screens.Screen previous) {
        if (previous instanceof dev.streamable.ui.StreamAbleScreen || !config.video.hideStudioFromOutputs) {
            return;
        }
        if (!sources.snapshot().isEmpty() || video.isActive()) {
            // The per-frame snapshot is current and clean; the framebuffer now
            // also holds locally drawn sources, so it must not be copied.
            return;
        }
        try {
            compositor.snapshotGame();
        } catch (RuntimeException e) {
            StreamAbleLog.COMPOSITOR.debug("Could not freeze the game frame", e);
        }
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
        dev.streamable.compat.voicechat.SimpleVoiceChatSupport.tick(Minecraft.getInstance());
        if (!dev.streamable.compat.plasmovoice.PlasmoVoiceSupport.isSettled()) {
            tryRegisterVoiceChat();
        }
        browsers.tick(sources);
        streaming.tick();
        tickReplayBuffer(Minecraft.getInstance());
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
            if (!frozen) {
                // Keep a clean copy of the game frame - taken before the local
                // overlay below draws sources onto the screen - for the moment
                // a Stream-able screen opens and outputs freeze on it.
                compositor.snapshotGame();
            }
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
        if (client.screen != null && !editing) {
            // Sources belong over gameplay (and in the canvas editor), never
            // over a menu or the Studio.
            return;
        }
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
            compositor.drawOnScreen((w, h) -> EditorOverlayRenderer.render(compositor.quadRenderer(), editor,
                    new ProgramCanvas(canvas).mappingTo(w, h), w, h));
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
        if (recording.isActive() || streaming.isLive() || replayBuffer.isRunning()) {
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
                config.video.streaming.effectiveMode(), config.streaming.fps, streaming::submitFrame,
                () -> config.video.watermark.onStream ? config.video.watermark : null));
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
                config.video.recording.effectiveMode(), config.recording.fps, recording::submitFrame,
                () -> config.video.watermark.onRecording ? config.video.watermark : null));
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

    // ---- replay buffer and clips ------------------------------------------------

    /**
     * Starts the replay buffer at the recording's size, encoder and quality.
     *
     * @return {@code null} when started, otherwise why not
     */
    public String startReplayBuffer() {
        if (replayBuffer.isRunning()) {
            return null;
        }
        attachMixer();
        Resolution output = recordingOutput();
        String error = replayBuffer.start(config.recording, output);
        if (error != null) {
            detachMixerIfIdle();
            return error;
        }
        video.startReplayOutput(new VideoPipeline.OutputConfig("Replay buffer", output,
                config.video.recording.effectiveMode(), config.recording.fps, replayBuffer::submitFrame,
                () -> config.video.watermark.onRecording ? config.video.watermark : null));
        applyMicrophoneSettings();
        return null;
    }

    public void stopReplayBuffer() {
        video.stopReplayOutput();
        replayBuffer.stop();
        applyMicrophoneSettings();
        detachMixerIfIdle();
    }

    /**
     * Saves the last {@code replayBufferSeconds} as a clip, in the background.
     * The result is announced on the stream HUD (never in outputs).
     */
    public java.util.concurrent.CompletableFuture<Path> saveReplay(String reason) {
        java.util.concurrent.CompletableFuture<Path> saving = replayBuffer.save(reason, config.recording.replayBufferSeconds,
                replayBuffer.clipsDirectory(recording.outputDirectory(config.recording)),
                new dev.streamable.ffmpeg.AudioProfile(config.recording.audioCodec, config.recording.audioBitrateKbps,
                        config.recording.audioSampleRate, 2));
        saving.whenComplete((file, error) -> Minecraft.getInstance().execute(() -> {
            if (error == null) {
                dev.streamable.ui.StreamHud.flash("Clip saved: " + file.getFileName(), 0xFF3DD68C);
            } else {
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                if (cause instanceof java.io.UncheckedIOException io && io.getCause() != null) {
                    cause = io.getCause();
                }
                dev.streamable.ui.StreamHud.flash("Clip not saved: " + cause.getMessage(), 0xFFFF5C6C);
                StreamAbleLog.RECORDING.warn("Clip not saved: {}", cause.getMessage());
            }
        }));
        return saving;
    }

    /** Called by the toast mixin when the game shows an advancement toast. */
    public void noteAdvancementEarned() {
        advancementEarned = true;
    }

    /** Called by the attack callback; remembers the target so its death can count as a kill. */
    public void noteAttack(net.minecraft.world.entity.Entity target) {
        if (target instanceof net.minecraft.world.entity.LivingEntity living
                && !(target instanceof net.minecraft.world.entity.decoration.ArmorStand)) {
            lastAttacked = living;
            lastAttackNanos = System.nanoTime();
        }
    }

    /** Auto start/stop with the world, and automatic clips. Client thread. */
    private void tickReplayBuffer(Minecraft client) {
        boolean inWorld = client.level != null && client.player != null;
        if (inWorld != wasInWorld) {
            wasInWorld = inWorld;
            if (inWorld && config.recording.replayBufferEnabled && !replayBuffer.isRunning()) {
                String error = startReplayBuffer();
                if (error != null) {
                    StreamAbleLog.RECORDING.warn("Replay buffer did not start: {}", error);
                }
            } else if (!inWorld && replayBuffer.isRunning()) {
                stopReplayBuffer();
            }
        }
        if (!replayBuffer.isRunning() && video.stats().replay() != null) {
            video.stopReplayOutput();   // the encoder died; release its capture
            detachMixerIfIdle();
        }
        long now = System.nanoTime();
        boolean killed = false;
        if (lastAttacked != null) {
            if (lastAttacked.isDeadOrDying()) {
                killed = now - lastAttackNanos < 5_000_000_000L;
                lastAttacked = null;
            } else if (now - lastAttackNanos > 5_000_000_000L || lastAttacked.isRemoved()) {
                lastAttacked = null;
            }
        }
        boolean advancement = advancementEarned;
        advancementEarned = false;
        var observation = new dev.streamable.recording.replay.ClipTriggers.Observation(inWorld,
                inWorld && client.player.isDeadOrDying(),
                inWorld ? client.level.dimension().identifier().toString() : null, killed);
        var due = clipTriggers.tick(now, observation, enabledClipReasons(), advancement);
        if (!due.isEmpty() && replayBuffer.isRunning()) {
            saveReplay(dev.streamable.recording.replay.ClipTriggers.tag(due));
        }
    }

    private java.util.Set<dev.streamable.recording.replay.ClipTriggers.Reason> enabledClipReasons() {
        var set = java.util.EnumSet.noneOf(dev.streamable.recording.replay.ClipTriggers.Reason.class);
        if (config.recording.autoClipOnDeath) {
            set.add(dev.streamable.recording.replay.ClipTriggers.Reason.DEATH);
        }
        if (config.recording.autoClipOnKill) {
            set.add(dev.streamable.recording.replay.ClipTriggers.Reason.KILL);
        }
        if (config.recording.autoClipOnAdvancement) {
            set.add(dev.streamable.recording.replay.ClipTriggers.Reason.ADVANCEMENT);
        }
        if (config.recording.autoClipOnDimensionChange) {
            set.add(dev.streamable.recording.replay.ClipTriggers.Reason.DIMENSION);
        }
        return set;
    }

    public StreamHealth health() {
        return streaming.health();
    }

    public dev.streamable.streaming.test.StreamTestController streamTests() {
        return streamTests;
    }

    /**
     * Starts a destination test with the current streaming settings. Refused
     * while live: the test would compete with the broadcast for the encoder
     * and the uplink it is trying to measure.
     *
     * @return {@code null} when started, otherwise why not
     */
    public String startDestinationTest(StreamDestination destination, int seconds) {
        VideoEncoder encoder = encoderProbe.resolve(config.streaming.encoder, true);
        if (encoder == null) {
            return "No usable stream encoder was found yet.";
        }
        return streamTests.start(destination, config.streaming.encodeProfile(encoder, streamingOutput()),
                ffmpeg.resolution().executable(), seconds, streaming.isLive());
    }

    /**
     * Stream Health report, rebuilt at most twice a second so that drawing it
     * every frame costs nothing. Free disk space is sampled every 5 seconds.
     */
    public dev.streamable.diagnostics.HealthReport healthReport() {
        long now = System.currentTimeMillis();
        if (cachedHealth != null && now - cachedHealthAt < 500) {
            return cachedHealth;
        }
        boolean recordingActive = recording.isActive();
        if (now - cachedFreeDiskAt > 5_000) {
            cachedFreeDisk = recording.freeDiskBytes();
            cachedFreeDiskAt = now;
        }
        boolean micCapturing = microphone.isCapturing();
        cachedHealth = dev.streamable.diagnostics.HealthReport.build(new dev.streamable.diagnostics.HealthReport.Inputs(
                video.stats(), health(), recordingActive,
                recordingActive ? recording.elapsedMillis() : 0,
                recordingActive ? recording.currentFileSizeBytes() : 0,
                cachedFreeDisk, config.recording.bitrateKbps,
                micCapturing ? microphone.processor().stats() : null,
                microphone.noise().status(), micCapturing));
        cachedHealthAt = now;
        return cachedHealth;
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
            streamTests.cancel();
            replayBuffer.close();
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
