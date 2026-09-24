package dev.streamable.diagnostics;

import dev.streamable.StreamAbleClient;
import dev.streamable.audio.AudioBus;
import dev.streamable.browser.audio.BrowserAudioBridge;
import dev.streamable.compat.plasmovoice.PlasmoVoiceSupport;
import dev.streamable.config.MicrophoneSettings;
import dev.streamable.ffmpeg.FFmpegCapabilityProbe;
import dev.streamable.ffmpeg.FFmpegManager;
import dev.streamable.pipeline.VideoPipeline;
import dev.streamable.runtime.ManagedRuntime;
import dev.streamable.streaming.StreamDestination;
import dev.streamable.video.Resolution;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gathers the live state for {@link DiagnosticsReport}. Runs on the render
 * thread (it reads the GL renderer string). Home-directory paths are shown as
 * {@code ~} so the report does not reveal the account name.
 */
public final class ClientDiagnostics {

    private ClientDiagnostics() {
    }

    public static String collect(StreamAbleClient client, String modVersion) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> secrets = new ArrayList<>();
        for (StreamDestination d : client.streaming().destinations()) {
            secrets.add(d.credentials().streamKey());
        }

        Minecraft mc = Minecraft.getInstance();
        List<String> env = new ArrayList<>();
        env.add("Stream-able: " + modVersion);
        env.add("Minecraft: " + SharedConstants.getCurrentVersion().name());
        env.add("Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        env.add("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        env.add("GPU: " + safeGl(GL11.GL_RENDERER) + " / " + safeGl(GL11.GL_VENDOR) + " / GL " + safeGl(GL11.GL_VERSION));
        if (mc.getWindow() != null) {
            env.add("Window: " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight()
                    + ", GUI scale " + mc.getWindow().getGuiScale() + ", " + mc.getFps() + " FPS");
        }
        sections.put("Environment", env);

        List<String> ffmpeg = new ArrayList<>();
        FFmpegManager.Resolution resolution = client.ffmpeg().resolution();
        ffmpeg.add("In use: " + (resolution.isAvailable() ? resolution.version() : "none") + " - " + resolution.describe());
        FFmpegCapabilityProbe probe = client.encoderProbe();
        if (probe.isComplete()) {
            for (FFmpegCapabilityProbe.Result r : probe.results()) {
                ffmpeg.add(String.format(Locale.ROOT, "  %-18s %s%s", r.encoder().ffmpegName(),
                        r.usable() ? "usable" : "unusable: " + r.reason(),
                        r.usable() && r.framesPerSecond() > 0 ? String.format(Locale.ROOT, " (%.0f FPS test)", r.framesPerSecond()) : ""));
            }
        } else {
            ffmpeg.add("Encoder test: not finished");
        }
        sections.put("FFmpeg", ffmpeg);

        List<String> runtimes = new ArrayList<>();
        for (ManagedRuntime runtime : client.runtimes().all()) {
            runtimes.add(runtime.id() + " " + runtime.version() + ": " + runtime.progress().summary()
                    + (runtime.progress().detail() == null || runtime.progress().detail().isBlank() ? ""
                    : " - " + runtime.progress().detail()));
        }
        sections.put("Components", runtimes);

        List<String> video = new ArrayList<>();
        Resolution canvas = client.canvasResolution();
        Resolution game = StreamAbleClient.gameResolution();
        var vs = client.config().video;
        video.add("Canvas: " + canvas.label() + " (" + canvas.marketedRatio() + "), game " + (game == null ? "?" : game.label())
                + " mapped " + vs.gameScaling);
        video.add("Recording output: " + client.recordingOutput().label() + " " + vs.recording.effectiveMode()
                + " @ " + client.config().recording.fps + " FPS, encoder '" + client.config().recording.encoder
                + "' -> " + client.recording().plannedEncoder(client.config().recording).ffmpegName()
                + ", " + client.config().recording.container + ", " + client.config().recording.rateControl);
        video.add("Stream output: " + client.streamingOutput().label() + " " + vs.streaming.effectiveMode()
                + " @ " + client.config().streaming.fps + " FPS, " + client.config().streaming.bitrateKbps + " kbps, encoder '"
                + client.config().streaming.encoder + "' -> " + probe.resolve(client.config().streaming.encoder, true).ffmpegName());
        VideoPipeline.Stats stats = client.video().stats();
        video.add(String.format(Locale.ROOT, "Pipeline: render %.1f FPS, compose %.2f ms, frozen %s",
                stats.renderFps(), stats.composeMillis(), stats.frozen()));
        for (VideoPipeline.OutputStats o : stats.outputs()) {
            video.add(String.format(Locale.ROOT, "  %s: %s %s capture %.1f/%d FPS, readback %.2f ms, repeats %d "
                            + "(largest %d), readback skipped %d, buffers exhausted %d, %s",
                    o.name(), o.resolution().label(), o.mode(), o.captureFps(), o.targetFps(), o.readbackMillis(),
                    o.renderRepeats(), o.largestCatchUp(), o.readbackSkipped(), o.bufferExhausted(),
                    o.broken() ? "BROKEN" : "ok"));
        }
        sections.put("Video", video);

        List<String> audio = new ArrayList<>();
        MicrophoneSettings mic = client.config().microphone;
        audio.add("Microphone: " + (client.config().recording.captureMicrophone ? "on" : "off") + ", source " + mic.source
                + ", channel " + mic.inputChannel + ", " + (mic.device.isBlank() ? "system default device" : "device '" + mic.device + "'")
                + ", status: " + client.microphone().status());
        audio.add("Chain: preset '" + mic.preset + "', processing " + mic.processingEnabled + ", input " + mic.inputGainDb
                + " dB, NC " + mic.noise.level + "/" + mic.noise.backend + ", gate " + mic.gate.enabled + ", EQ " + mic.eq.enabled
                + ", de-esser " + mic.deEsser.enabled + ", compressor " + mic.compressor.enabled + ", AGC " + mic.agc.enabled
                + ", limiter " + mic.limiter.enabled + ", muted " + mic.muted + ", PTT " + mic.pushToTalk);
        var nc = client.microphone().noise().status();
        audio.add("Noise cancellation: " + nc.state() + " " + nc.activeModel() + " - " + nc.detail()
                + (nc.fallbacks().isEmpty() ? "" : " [fallbacks: " + String.join("; ", nc.fallbacks()) + "]"));
        if (client.microphone().isCapturing()) {
            var p = client.microphone().processor().stats();
            audio.add(String.format(Locale.ROOT, "DSP: %.2f ms/block (peak %.2f), latency %.1f ms, backlog %d, "
                            + "dropped %d, overruns %d, underruns %d, overloaded %s", p.dspMillis(), p.dspPeakMillis(),
                    p.chainLatencyMillis(), p.backlogBlocks(), p.droppedBlocks(), p.overrunEvents(), p.underruns(), p.overloaded()));
        }
        for (AudioBus bus : client.audioMixer().buses()) {
            audio.add(String.format(Locale.ROOT, "Bus %s: volume %.2f, muted %s, trimmed %d bytes", bus.kind(),
                    bus.volume(), bus.muted(), client.audioMixer().trimmedBytes(bus.kind())));
        }
        audio.add("Plasmo Voice: " + PlasmoVoiceSupport.statusLine());
        audio.add("Browser audio: " + BrowserAudioBridge.capability());
        sections.put("Audio", audio);

        List<String> outputs = new ArrayList<>();
        outputs.add("Browser engine: " + client.browsers().status().shortLabel() + ", " + client.sources().size()
                + " sources, " + client.browsers().liveBrowserCount() + " browsers running");
        outputs.add("Recording: " + client.recording().state() + blankOr(" - last error: ", client.recording().lastError()));
        outputs.add("Streaming: " + client.streaming().state() + blankOr(" - last error: ", client.streaming().lastError()));
        for (StreamDestination d : client.streaming().destinations()) {
            outputs.add("  " + d.name() + " (" + d.platform() + ", " + (d.enabled() ? "enabled" : "disabled") + "): "
                    + d.state() + ", " + d.credentials().redactedPublishUrl()
                    + (d.credentials().streamKey().isEmpty() ? ", no key" : ", key set")
                    + blankOr(", last error: ", d.lastError()));
        }
        sections.put("Outputs", outputs);

        List<String> health = new ArrayList<>();
        HealthReport report = client.healthReport();
        for (HealthReport.Finding f : report.findings()) {
            health.add(f.severity() + ": " + f.message());
        }
        if (health.isEmpty()) {
            health.add("No findings.");
        }
        sections.put("Health", health);

        String text = DiagnosticsReport.build(new DiagnosticsReport.Input(sections, secrets));
        String home = System.getProperty("user.home");
        return home == null || home.length() < 3 ? text : text.replace(home, "~");
    }

    private static String blankOr(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + value;
    }

    private static String safeGl(int name) {
        try {
            String value = GL11.glGetString(name);
            return value == null ? "?" : value;
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
