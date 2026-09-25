package dev.streamable.ui.studio;

import dev.streamable.StreamAbleClient;
import dev.streamable.audio.ai.NoiseCancellationManager;
import dev.streamable.audio.ai.NoiseCancellationStage;
import dev.streamable.audio.dsp.MicrophoneChain;
import dev.streamable.audio.mic.MicrophoneCalibration;
import dev.streamable.audio.mic.MicrophonePresets;
import dev.streamable.audio.mic.MicrophoneService;
import dev.streamable.audio.mic.MicrophoneTest;
import dev.streamable.compat.plasmovoice.PlasmoVoiceSupport;
import dev.streamable.compat.voicechat.SimpleVoiceChatSupport;
import dev.streamable.config.MicrophoneSettings;
import dev.streamable.config.MicrophoneSettings.EqBand;
import dev.streamable.ui.kit.Button;
import dev.streamable.ui.kit.Collapsible;
import dev.streamable.ui.kit.Dialog;
import dev.streamable.ui.kit.Dropdown;
import dev.streamable.ui.kit.IconButton;
import dev.streamable.ui.kit.Icons;
import dev.streamable.ui.kit.Label;
import dev.streamable.ui.kit.Layouts;
import dev.streamable.ui.kit.Segmented;
import dev.streamable.ui.kit.Slider;
import dev.streamable.ui.kit.TextField;
import dev.streamable.ui.kit.Theme;
import dev.streamable.ui.kit.Toggle;
import dev.streamable.ui.kit.Widgets;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Audio: the microphone and its processing chain.
 *
 * <p>Simple mode covers what most players need - device, preset, noise
 * cancellation strength, level, calibration and a test recording. Advanced
 * mode exposes every stage of the chain with live gain-reduction readouts
 * and diagnostics. Everything runs locally; nothing is sent anywhere.</p>
 */
final class AudioPage {

    private AudioPage() {
    }

    static void build(Studio s, Layouts.Column page) {
        StreamAbleClient client = s.client();
        MicrophoneSettings mic = client.config().microphone;

        Layouts.Row mode = page.add(new Layouts.Row(Theme.SPACE_4));
        mode.add(new Segmented(List.of("Simple", "Advanced"), () -> mic.advancedMode ? 1 : 0, i -> {
            mic.advancedMode = i == 1;
            s.changed();
            s.screen().refresh();
        }).tooltip("Advanced shows every processing stage and diagnostics."), 150);
        mode.add(new Label(() -> "All processing runs on this computer; no audio leaves it except in your "
                + "recording or stream.").color(Theme.TEXT_MUTED).scale(Theme.TEXT_CAPTION), -1);

        microphoneCard(s, page.add(new Widgets.Card(Theme.SPACE_5)));
        page.add(new Widgets.Notice(() -> client.config().recording.captureMicrophone ? null
                : "The microphone is off. Turn it on above to set it up; meters, tests and calibration need it running.",
                () -> Theme.INFO));
        Layouts.Column enabled = page.add(new Layouts.Column(Theme.SECTION_GAP));
        enabled.visibleWhen(() -> client.config().recording.captureMicrophone);
        soundCard(s, enabled.add(new Widgets.Card(Theme.SPACE_5)));
        testCard(s, enabled.add(new Widgets.Card(Theme.SPACE_5)));
        if (mic.advancedMode) {
            chainCard(s, enabled.add(new Widgets.Card(Theme.SPACE_4)));
            presetsCard(s, enabled.add(new Widgets.Card(Theme.SPACE_5)));
            diagnosticsCard(s, enabled.add(new Widgets.Card(Theme.SPACE_4)));
        }
        controlsCard(s, enabled.add(new Widgets.Card(Theme.SPACE_5)));
    }

    // ---- device ---------------------------------------------------------------------------

    private static void microphoneCard(Studio s, Widgets.Card card) {
        StreamAbleClient client = s.client();
        MicrophoneService service = client.microphone();
        MicrophoneSettings mic = client.config().microphone;
        if (!service.devices().hasScanned() && !service.devices().isScanning()) {
            service.devices().refreshAsync();
        }
        card.add(new Widgets.SectionHeader("Microphone", service::status));
        card.add(Toggle.of("Use my microphone", () -> client.config().recording.captureMicrophone, v -> {
            client.config().recording.captureMicrophone = v;
            client.applyMicrophoneSettings();
            s.screen().updateMicrophoneUser();
            s.changed();
        }).detail(() -> "Included in recordings and streams. Starts only while one of them runs (and while this page is open)."));

        Layouts.Grid grid = card.add(new Layouts.Grid(170, Theme.SPACE_5));
        grid.visibleWhen(() -> client.config().recording.captureMicrophone);
        List<MicrophoneSettings.Source> sources = new ArrayList<>();
        sources.add(MicrophoneSettings.Source.SYSTEM);
        if (PlasmoVoiceSupport.isInstalled()) {
            sources.add(MicrophoneSettings.Source.PLASMO_VOICE);
        }
        if (SimpleVoiceChatSupport.isInstalled()) {
            sources.add(MicrophoneSettings.Source.SIMPLE_VOICE_CHAT);
        }
        if (sources.size() > 1 || mic.source != MicrophoneSettings.Source.SYSTEM) {
            grid.add(new Dropdown("Source", () -> sources.stream().map(AudioPage::sourceName).toList(),
                    () -> sources.indexOf(mic.source), i -> {
                mic.source = sources.get(i);
                client.applyVoiceChatSettings();
                s.micChanged();
            }).placeholder(() -> sourceName(mic.source) + " (not installed)")
                    .tooltip("Use a device directly, or the microphone a voice-chat mod already captures - then "
                            + "viewers hear exactly what other players hear, only while you transmit."));
        }
        Layouts.Row device = grid.add(new Layouts.Row(Theme.SPACE_3));
        device.visibleWhen(() -> service.effectiveSource() == MicrophoneSettings.Source.SYSTEM);
        device.add(new Dropdown("Device", () -> deviceNames(client), () -> deviceNames(client).indexOf(
                mic.device.isBlank() ? "System default" : mic.device), i -> {
            mic.device = i == 0 ? "" : deviceNames(client).get(i);
            s.changed();
            client.restartMicrophone();
        }).placeholder(() -> mic.device.isBlank() ? "System default" : mic.device + " (not connected)"), -1);
        device.add(IconButton.of(Icons.Icon.REFRESH, "Rescan devices", () -> {
            service.devices().refreshAsync();
            s.toast("Scanning for microphones...");
        }), 18);
        grid.add(s.enumDropdown("Input channel", MicrophoneSettings.InputChannel.values(), v -> switch (v) {
            case AUTO -> "Auto (detect a mono mic)";
            case MIX -> "Mix left + right";
            case LEFT -> "Left / input 1";
            case RIGHT -> "Right / input 2";
        }, () -> mic.inputChannel, v -> {
            mic.inputChannel = v;
            s.micChanged();
        }).tooltip("Audio interfaces often put a single mic on input 1 only. Auto picks the live channel so "
                + "your voice is never half as loud or on one side.")).visibleWhen(() -> service.effectiveSource() == MicrophoneSettings.Source.SYSTEM);
        grid.add(Toggle.of("Also process with Stream-able's chain", () -> mic.processPlasmoVoice, v -> {
            mic.processPlasmoVoice = v;
            s.micChanged();
        }).tooltip("The voice-chat mod already processes its microphone; add Stream-able's chain on top.")
        ).visibleWhen(() -> service.effectiveSource() != MicrophoneSettings.Source.SYSTEM);
        card.add(new Widgets.Notice(service::plasmoWarning, () -> Theme.WARNING));

        MicrophoneChain chain = service.processor().chain();
        Layouts.Adaptive meters = card.add(new Layouts.Adaptive(360, Theme.SPACE_6));
        meters.visibleWhen(() -> client.config().recording.captureMicrophone);
        meters.add(new Widgets.Meter("Input (raw)", chain::inputLevel, true));
        meters.add(new Widgets.Meter("Output (processed)", chain::outputLevel, true));
    }

    static String sourceName(MicrophoneSettings.Source source) {
        return switch (source) {
            case SYSTEM -> "Microphone device";
            case PLASMO_VOICE -> "Plasmo Voice microphone";
            case SIMPLE_VOICE_CHAT -> "Simple Voice Chat microphone";
        };
    }

    private static List<String> deviceNames(StreamAbleClient client) {
        List<String> names = new ArrayList<>();
        names.add("System default");
        for (var device : client.microphone().devices().devices()) {
            names.add(device.name());
        }
        return names;
    }

    // ---- simple controls ----------------------------------------------------------------------

    private static List<String> chainPresetNames(MicrophoneSettings mic) {
        List<String> names = new ArrayList<>(MicrophonePresets.CHAIN.keySet());
        for (MicrophoneSettings.CustomPreset custom : mic.customPresets) {
            names.add(custom.name);
        }
        return names;
    }

    private static void soundCard(Studio s, Widgets.Card card) {
        StreamAbleClient client = s.client();
        MicrophoneSettings mic = client.config().microphone;
        NoiseCancellationManager noise = client.microphone().noise();
        card.add(new Widgets.SectionHeader("Sound", () -> "Preset: " + mic.preset
                + (mic.processingEnabled ? "" : "  ·  processing bypassed")));

        Layouts.Grid grid = card.add(new Layouts.Grid(190, Theme.SPACE_5));
        grid.add(new Dropdown("Preset", () -> chainPresetNames(mic), () -> chainPresetNames(mic).indexOf(mic.preset), i -> {
            String name = chainPresetNames(mic).get(i);
            MicrophonePresets.applyChain(name, mic);
            s.micChanged();
            s.toast("Applied the " + name + " preset.");
        }).placeholder(() -> mic.preset + " (edited)").tooltip("A starting point for the whole chain; adjust from there."));
        grid.add(Toggle.of("Processing", () -> mic.processingEnabled, v -> {
            mic.processingEnabled = v;
            s.micChanged();
        }).detail(() -> "Off sends the raw microphone to the mix."));

        card.add(new Widgets.Caption("AI noise cancellation"));
        card.add(new Segmented(List.of("Off", "Light", "Balanced", "Strong"), () -> mic.noise.level.ordinal(), i -> {
            mic.noise.level = MicrophoneSettings.NoiseLevel.values()[i];
            s.micChanged();
        }).tooltip("Removes fans, keyboards and room noise with a neural network running on this computer. "
                + "Stronger removes more but can colour your voice."));
        card.add(new Label(() -> noiseLine(noise.status())).color(() -> noiseColor(noise.status()))
                .scale(Theme.TEXT_CAPTION).wrap());

        card.add(slider("Input gain", -24, 36, 0.5, () -> mic.inputGainDb, v -> mic.inputGainDb = v, s)
                .format(v -> String.format(Locale.ROOT, "%+.1f dB", v)).defaultValue(0)
                .tooltip("Digital gain before processing. Calibration sets it for you."));

        // Calibration
        MicrophoneCalibration calibration = client.microphone().calibration();
        card.add(new Widgets.Caption("Calibration"));
        Layouts.Row calRow = card.add(new Layouts.Row(Theme.SPACE_3));
        calRow.add(new Button(() -> calibrating(calibration) ? "Cancel" : "Calibrate", () -> {
            if (calibrating(calibration)) {
                calibration.cancel();
            } else {
                calibration.start(true);
            }
        }).icon(Icons.Icon.MIC).tooltip("Measures room noise and your speaking level, then recommends input gain "
                + "and a gate threshold. Nothing changes until you apply it."), 88);
        calRow.add(Button.of("Skip loud step", calibration::skipLoud).variant(Button.Variant.GHOST)
                .visibleWhen(() -> calibration.step() == MicrophoneCalibration.Step.LOUD), 100);
        calRow.add(new Label(() -> calibration.step().instruction()).color(Theme.TEXT_SECONDARY), -1);
        card.add(new Widgets.ProgressBar(calibration::stepProgress, () -> Theme.ACCENT)).visibleWhen(() -> calibrating(calibration));
        card.add(new Label(() -> calibrationSummary(calibration.result())).color(Theme.TEXT_SECONDARY)
                .scale(Theme.TEXT_CAPTION).wrap()).visibleWhen(() -> calibration.step() == MicrophoneCalibration.Step.DONE
                && calibration.result() != null);
        card.add(Button.of("Apply recommendations", () -> {
            MicrophoneCalibration.apply(calibration.result(), mic);
            s.micChanged();
            s.toast("Calibration applied.");
        }).variant(Button.Variant.PRIMARY)).visibleWhen(() -> calibration.step() == MicrophoneCalibration.Step.DONE
                && calibration.result() != null);
    }

    private static boolean calibrating(MicrophoneCalibration calibration) {
        MicrophoneCalibration.Step step = calibration.step();
        return step != MicrophoneCalibration.Step.IDLE && step != MicrophoneCalibration.Step.DONE;
    }

    private static String calibrationSummary(MicrophoneCalibration.Result r) {
        if (r == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "Room noise %.0f dBFS · speech %.0f dBFS RMS, peaks %.0f dBFS. Recommended: input gain %+.1f dB, "
                        + "gate threshold %.0f dB.", r.noiseFloorDb(), r.speechRmsDb(), r.speechPeakDb(),
                r.recommendedInputGainDb(), r.recommendedGateDb()));
        for (String advice : r.advice()) {
            text.append('\n').append("• ").append(advice);
        }
        return text.toString();
    }

    static String noiseLine(NoiseCancellationManager.Status status) {
        return switch (status.state()) {
            case OFF -> "Off.";
            case LOADING -> status.detail();
            case UNAVAILABLE -> status.detail();
            case ACTIVE, DEGRADED -> String.format(Locale.ROOT, "%s · %.0f ms latency · %.2f ms per 10 ms hop (%.0f%% of real time)%s",
                    status.activeModel(), status.latencyMillis(), status.inferenceMillis(), status.realTimeFactor() * 100,
                    status.state() == NoiseCancellationManager.Status.State.DEGRADED ? " · " + status.detail() : "");
        };
    }

    static int noiseColor(NoiseCancellationManager.Status status) {
        return switch (status.state()) {
            case OFF -> Theme.TEXT_MUTED;
            case LOADING -> Theme.INFO;
            case ACTIVE -> Theme.SUCCESS;
            case DEGRADED -> Theme.WARNING;
            case UNAVAILABLE -> Theme.DANGER;
        };
    }

    // ---- test & monitor -------------------------------------------------------------------------

    private static void testCard(Studio s, Widgets.Card card) {
        StreamAbleClient client = s.client();
        MicrophoneSettings mic = client.config().microphone;
        MicrophoneTest test = client.microphone().test();
        card.add(new Widgets.SectionHeader("Test", () -> "Record a few seconds, then compare the raw and processed sound."));
        Layouts.Row row = card.add(new Layouts.Row(Theme.SPACE_3));
        row.add(new Button(() -> test.state() == MicrophoneTest.State.RECORDING ? "Stop" : "Record 8 s", () -> {
            if (test.state() == MicrophoneTest.State.RECORDING) {
                test.stopRecording();
            } else {
                client.microphone().monitor().stop();
                test.record(8);
            }
        }).icon(() -> test.state() == MicrophoneTest.State.RECORDING ? Icons.Icon.STOP : Icons.Icon.RECORD), 84);
        row.add(Button.of("Play raw", test::playRaw).icon(Icons.Icon.PLAY)
                .enabledWhen(() -> test.hasClip() && test.state() != MicrophoneTest.State.RECORDING), 76);
        row.add(Button.of("Play processed", test::playProcessed).icon(Icons.Icon.PLAY).variant(Button.Variant.PRIMARY)
                .enabledWhen(() -> test.hasClip() && test.state() != MicrophoneTest.State.RECORDING), 104);
        row.add(Button.of("Stop", test::stopPlayback).variant(Button.Variant.GHOST)
                .visibleWhen(() -> test.state() == MicrophoneTest.State.PLAYING_RAW
                        || test.state() == MicrophoneTest.State.PLAYING_PROCESSED), 44);
        row.add(Layouts.spacer(0), -1);
        card.add(new Widgets.ProgressBar(() -> test.state() == MicrophoneTest.State.RECORDING
                ? test.recordedSeconds() / Math.max(1, test.targetSeconds()) : test.playbackProgress(),
                () -> test.state() == MicrophoneTest.State.RECORDING ? Theme.RECORDING : Theme.ACCENT))
                .visibleWhen(() -> test.state() != MicrophoneTest.State.IDLE && test.state() != MicrophoneTest.State.READY);
        card.add(new Label(() -> {
            double[] rms = test.rmsDb();
            return String.format(Locale.ROOT, "Clip level: raw %.1f dBFS RMS, processed %.1f dBFS RMS.", rms[0], rms[1]);
        }).color(Theme.TEXT_MUTED).scale(Theme.TEXT_CAPTION)).visibleWhen(test::hasClip);

        card.add(new Widgets.Divider());
        card.add(Toggle.of("Monitor live", () -> mic.monitoring, v -> {
            mic.monitoring = v;
            if (v) {
                client.microphone().test().stopPlayback();
            }
            client.microphone().reconcile();
        }).detail(() -> "Hear yourself through the processing chain. Use headphones: speakers will feed back."));
        Layouts.Grid monitor = card.add(new Layouts.Grid(170, Theme.SPACE_5));
        monitor.visibleWhen(() -> mic.monitoring);
        monitor.add(new Segmented(List.of("Processed", "Raw"), () -> mic.monitorProcessed ? 0 : 1, i -> {
            mic.monitorProcessed = i == 0;
            client.microphone().monitor().configure(mic.monitorProcessed, mic.monitorVolume);
            s.changed();
        }));
        monitor.add(new Slider("Monitor volume", 0, 2, 0.05, () -> mic.monitorVolume, v -> {
            mic.monitorVolume = v;
            client.microphone().monitor().configure(mic.monitorProcessed, mic.monitorVolume);
            s.changed();
        }).format(v -> Math.round(v * 100) + "%").defaultValue(0.8));
        card.add(new Widgets.Notice(() -> mic.monitoring
                ? "Monitoring is on. It turns off when you close the Studio and is never saved on." : null,
                () -> Theme.WARNING));
    }

    // ---- advanced: the chain ---------------------------------------------------------------------

    private static Slider slider(String label, double min, double max, double step, DoubleSupplier get,
                                 DoubleConsumer set, Studio s) {
        return new Slider(label, min, max, step, get, v -> {
            set.accept(v);
            s.micChanged();
        });
    }

    private static Collapsible stage(Studio s, Widgets.Card card, String title, String stageId,
                                     java.util.function.Supplier<String> summary,
                                     java.util.function.BooleanSupplier enabled,
                                     java.util.function.Consumer<Boolean> setEnabled) {
        MicrophoneSettings mic = s.client().config().microphone;
        Collapsible c = card.add(new Collapsible(title, summary, enabled, enabled == null ? null : v -> {
            setEnabled.accept(v);
            s.micChanged();
        }));
        c.body().add(Button.of("Reset " + title.toLowerCase(Locale.ROOT), () -> {
            MicrophonePresets.resetStage(stageId, mic);
            s.micChanged();
            s.screen().refresh();
        }).variant(Button.Variant.GHOST));
        return c;
    }

    private static String db(double v) {
        return String.format(Locale.ROOT, "%.1f dB", v);
    }

    private static String ms(double v) {
        return String.format(Locale.ROOT, "%.0f ms", v);
    }

    private static void chainCard(Studio s, Widgets.Card card) {
        StreamAbleClient client = s.client();
        MicrophoneSettings mic = client.config().microphone;
        MicrophoneChain chain = client.microphone().processor().chain();
        NoiseCancellationManager noise = client.microphone().noise();
        card.add(new Widgets.SectionHeader("Processing chain", () -> String.format(Locale.ROOT,
                "In order, top to bottom · total latency %.1f ms", chain.latencyMillis())));

        Collapsible hp = stage(s, card, "High-pass filter", "highpass",
                () -> mic.highPass.enabled ? Math.round(mic.highPass.frequencyHz) + " Hz, " + mic.highPass.slopeDbPerOctave
                        + " dB/oct" : "Off", () -> mic.highPass.enabled, v -> mic.highPass.enabled = v);
        hp.body().add(new Label(() -> "Removes rumble, desk bumps and DC offset below the corner frequency.")
                .color(Theme.TEXT_MUTED).scale(Theme.TEXT_CAPTION).wrap());
        hp.body().add(slider("Frequency", 20, 400, 1, () -> mic.highPass.frequencyHz, v -> mic.highPass.frequencyHz = v, s)
                .format(v -> Math.round(v) + " Hz").defaultValue(80));
        hp.body().add(new Segmented(List.of("12 dB/oct", "24 dB/oct"), () -> mic.highPass.slopeDbPerOctave >= 24 ? 1 : 0, i -> {
            mic.highPass.slopeDbPerOctave = i == 1 ? 24 : 12;
            s.micChanged();
        }));

        Collapsible ai = stage(s, card, "AI noise cancellation", "ai", () -> noiseLine(noise.status()), null, null);
        ai.body().add(new Segmented(List.of("Off", "Light", "Balanced", "Strong"), () -> mic.noise.level.ordinal(), i -> {
            mic.noise.level = MicrophoneSettings.NoiseLevel.values()[i];
            s.micChanged();
        }));
        ai.body().add(s.enumDropdown("Model", MicrophoneSettings.NoiseBackend.values(),
                MicrophoneSettings.NoiseBackend::displayName, () -> mic.noise.backend, v -> {
                    mic.noise.backend = v;
                    s.micChanged();
                }).tooltip("Auto tries DPDFNet (48 kHz), then DeepFilterNet2, then GTCRN, keeping the first that runs "
                + "in real time on this computer."));
        ai.body().add(slider("Maximum reduction", 0, 60, 1,
                () -> mic.noise.strengthOverrideDb < 0
                        ? NoiseCancellationStage.attenuationLimitDb(mic.noise.level) : mic.noise.strengthOverrideDb,
                v -> mic.noise.strengthOverrideDb = v, s).format(v -> Math.round(v) + " dB")
                .tooltip("How far noise may be pushed down."));
        ai.body().add(slider("Voice preservation", 0, 1, 0.01,
                () -> mic.noise.voicePreservation < 0
                        ? NoiseCancellationStage.defaultVoicePreservation(mic.noise.level) : mic.noise.voicePreservation,
                v -> mic.noise.voicePreservation = v, s).format(v -> Math.round(v * 100) + "%")
                .tooltip("Blends a little of the original voice back while you speak, for a more natural sound."));
        ai.body().add(Button.of("Use the level's defaults", () -> {
            mic.noise.strengthOverrideDb = -1;
            mic.noise.voicePreservation = -1;
            s.micChanged();
        }).variant(Button.Variant.GHOST).enabledWhen(() -> mic.noise.strengthOverrideDb >= 0 || mic.noise.voicePreservation >= 0));
        ai.body().add(Toggle.of("Bypass (compare)", () -> mic.noise.bypass, v -> {
            mic.noise.bypass = v;
            s.micChanged();
        }).detail(() -> "Temporary; also on the \"Toggle AI noise bypass\" hotkey."));
        ai.body().add(new Label(() -> {
            NoiseCancellationManager.Status st = noise.status();
            String fallbacks = st.fallbacks().isEmpty() ? "" : "\nFallbacks: " + String.join("; ", st.fallbacks());
            return (st.engineVersion().isEmpty() ? "" : "ONNX Runtime " + st.engineVersion() + ". ") + st.detail() + fallbacks;
        }).color(Theme.TEXT_MUTED).scale(Theme.TEXT_CAPTION).wrap());
        ai.body().add(Button.of("Retry loading", () -> {
            noise.retry(mic);
            s.toast("Reloading the noise model...");
        }).enabledWhen(() -> noise.status().state() == NoiseCancellationManager.Status.State.UNAVAILABLE));

        Collapsible gate = stage(s, card, "Noise gate / expander", "gate",
                () -> mic.gate.enabled ? String.format(Locale.ROOT, "%s · threshold %.0f dB · gain %s", chain.gate().isOpen()
                        ? "Open" : "Closed", mic.gate.thresholdDb, db(-chain.gate().gainReductionDb())) : "Off",
                () -> mic.gate.enabled, v -> mic.gate.enabled = v);
        gate.body().add(new Dropdown("Preset", () -> new ArrayList<>(MicrophonePresets.GATE.keySet()),
                () -> -1, i -> {
            MicrophonePresets.applyGate(new ArrayList<>(MicrophonePresets.GATE.keySet()).get(i), mic);
            s.micChanged();
        }).placeholder(() -> "Choose a room..."));
        gate.body().add(new Widgets.Meter("Gain reduction", () -> grReading(chain.gate().gainReductionDb()), false));
        gate.body().add(slider("Threshold", -90, -10, 0.5, () -> mic.gate.thresholdDb, v -> mic.gate.thresholdDb = v, s).format(AudioPage::db)
                .tooltip("The gate opens above this level. Set it just above your room noise (see calibration)."));
        gate.body().add(slider("Range", 0, 80, 1, () -> mic.gate.rangeDb, v -> mic.gate.rangeDb = v, s).format(AudioPage::db)
                .tooltip("How far quiet sections are turned down. A range rather than silence keeps breaths natural."));
        gate.body().add(slider("Hysteresis", 0, 20, 0.5, () -> mic.gate.hysteresisDb, v -> mic.gate.hysteresisDb = v, s).format(AudioPage::db));
        gate.body().add(slider("Expander ratio", 1, 20, 0.1, () -> mic.gate.ratio, v -> mic.gate.ratio = v, s)
                .format(v -> String.format(Locale.ROOT, "1:%.1f", v)));
        gate.body().add(slider("Attack", 0.1, 50, 0.1, () -> mic.gate.attackMs, v -> mic.gate.attackMs = v, s)
                .format(v -> String.format(Locale.ROOT, "%.1f ms", v)));
        gate.body().add(slider("Hold", 0, 1000, 5, () -> mic.gate.holdMs, v -> mic.gate.holdMs = v, s).format(AudioPage::ms));
        gate.body().add(slider("Release", 5, 2000, 5, () -> mic.gate.releaseMs, v -> mic.gate.releaseMs = v, s).format(AudioPage::ms));
        gate.body().add(slider("Look-ahead", 0, 10, 0.5, () -> mic.gate.lookaheadMs, v -> mic.gate.lookaheadMs = v, s)
                .format(v -> String.format(Locale.ROOT, "%.1f ms", v)).tooltip("Opens slightly early so word beginnings are never clipped."));

        Collapsible eq = stage(s, card, "Equalizer", "eq", () -> mic.eq.enabled ? mic.eq.preset + " · "
                + mic.eq.bands.size() + " bands" : "Off", () -> mic.eq.enabled, v -> mic.eq.enabled = v);
        eq.body().add(new Dropdown("Preset", () -> new ArrayList<>(MicrophonePresets.EQ.keySet()),
                () -> new ArrayList<>(MicrophonePresets.EQ.keySet()).indexOf(mic.eq.preset), i -> {
            MicrophonePresets.applyEq(new ArrayList<>(MicrophonePresets.EQ.keySet()).get(i), mic);
            s.micChanged();
            s.screen().refresh();
        }).placeholder(() -> "Custom"));
        eq.body().add(new Segmented(List.of("Before compressor", "After compressor"),
                () -> mic.eqPlacement.ordinal(), i -> {
            mic.eqPlacement = MicrophoneSettings.EqPlacement.values()[i];
            s.micChanged();
        }).tooltip("Before: the compressor reacts to the EQ'd voice. After: the EQ shapes the final sound."));
        eq.body().add(new EqGraph(s));
        for (int i = 0; i < mic.eq.bands.size(); i++) {
            eqBand(s, eq, mic.eq.bands.get(i), i);
        }
        eq.body().add(Button.of("Add band", () -> {
            mic.eq.bands.add(new EqBand(MicrophoneSettings.BandType.BELL, 1000, 0, 1.0));
            mic.eq.preset = "Custom";
            s.micChanged();
            s.screen().refresh();
        }).icon(Icons.Icon.PLUS).variant(Button.Variant.GHOST).enabledWhen(() -> mic.eq.bands.size() < 8));

        Collapsible deEss = stage(s, card, "De-esser", "deesser", () -> mic.deEsser.enabled
                ? String.format(Locale.ROOT, "%.0f Hz · reducing %.1f dB", mic.deEsser.frequencyHz, chain.deEsser().gainReductionDb())
                : "Off", () -> mic.deEsser.enabled, v -> mic.deEsser.enabled = v);
        deEss.body().add(new Widgets.Meter("Reduction", () -> grReading(chain.deEsser().gainReductionDb()), false));
        deEss.body().add(Toggle.of("Automatic frequency", () -> mic.deEsser.auto, v -> {
            mic.deEsser.auto = v;
            s.micChanged();
        }).detail(() -> "Follows the sibilance in the 5-8 kHz region."));
        deEss.body().add(slider("Frequency", 2000, 12000, 50, () -> mic.deEsser.frequencyHz, v -> mic.deEsser.frequencyHz = v, s)
                .format(v -> Math.round(v) + " Hz").enabledWhen(() -> !mic.deEsser.auto));
        deEss.body().add(slider("Threshold", -60, 0, 0.5, () -> mic.deEsser.thresholdDb, v -> mic.deEsser.thresholdDb = v, s).format(AudioPage::db));
        deEss.body().add(slider("Amount", 0, 18, 0.5, () -> mic.deEsser.amountDb, v -> mic.deEsser.amountDb = v, s).format(AudioPage::db));

        Collapsible comp = stage(s, card, "Compressor", "compressor", () -> mic.compressor.enabled
                ? String.format(Locale.ROOT, "%s · %.0f dB, %.1f:1 · reducing %.1f dB", mic.compressor.preset,
                mic.compressor.thresholdDb, mic.compressor.ratio, chain.compressor().gainReductionDb()) : "Off",
                () -> mic.compressor.enabled, v -> mic.compressor.enabled = v);
        comp.body().add(new Dropdown("Preset", () -> new ArrayList<>(MicrophonePresets.COMPRESSOR.keySet()),
                () -> new ArrayList<>(MicrophonePresets.COMPRESSOR.keySet()).indexOf(mic.compressor.preset), i -> {
            MicrophonePresets.applyCompressor(new ArrayList<>(MicrophonePresets.COMPRESSOR.keySet()).get(i), mic);
            s.micChanged();
        }).placeholder(() -> "Custom"));
        comp.body().add(new Widgets.Meter("Gain reduction", () -> grReading(chain.compressor().gainReductionDb()), false));
        comp.body().add(slider("Threshold", -60, 0, 0.5, () -> mic.compressor.thresholdDb, v -> mic.compressor.thresholdDb = v, s).format(AudioPage::db));
        comp.body().add(slider("Ratio", 1, 20, 0.1, () -> mic.compressor.ratio, v -> mic.compressor.ratio = v, s)
                .format(v -> String.format(Locale.ROOT, "%.1f:1", v)));
        comp.body().add(slider("Attack", 0.1, 200, 0.1, () -> mic.compressor.attackMs, v -> mic.compressor.attackMs = v, s)
                .format(v -> String.format(Locale.ROOT, "%.1f ms", v)));
        comp.body().add(slider("Release", 10, 2000, 5, () -> mic.compressor.releaseMs, v -> mic.compressor.releaseMs = v, s).format(AudioPage::ms));
        comp.body().add(slider("Knee", 0, 24, 0.5, () -> mic.compressor.kneeDb, v -> mic.compressor.kneeDb = v, s).format(AudioPage::db));
        comp.body().add(Toggle.of("Automatic make-up gain", () -> mic.compressor.autoMakeup, v -> {
            mic.compressor.autoMakeup = v;
            s.micChanged();
        }));
        comp.body().add(slider("Make-up gain", -12, 24, 0.5, () -> mic.compressor.makeupDb, v -> mic.compressor.makeupDb = v, s)
                .format(v -> String.format(Locale.ROOT, "%+.1f dB", v)).enabledWhen(() -> !mic.compressor.autoMakeup));

        Collapsible agc = stage(s, card, "Automatic gain", "agc", () -> mic.agc.enabled
                ? String.format(Locale.ROOT, "Target %.0f dB · now %+.1f dB", mic.agc.targetDb, chain.agc().currentGainDb()) : "Off",
                () -> mic.agc.enabled, v -> mic.agc.enabled = v);
        agc.body().add(new Label(() -> "Slowly steers your long-term level toward the target. It rides between "
                + "sentences, never within words.").color(Theme.TEXT_MUTED).scale(Theme.TEXT_CAPTION).wrap());
        agc.body().add(slider("Target", -40, -6, 0.5, () -> mic.agc.targetDb, v -> mic.agc.targetDb = v, s).format(AudioPage::db));
        agc.body().add(slider("Maximum gain", 0, 30, 0.5, () -> mic.agc.maxGainDb, v -> mic.agc.maxGainDb = v, s).format(AudioPage::db));
        agc.body().add(slider("Speed", 0.1, 12, 0.1, () -> mic.agc.speedDbPerSecond, v -> mic.agc.speedDbPerSecond = v, s)
                .format(v -> String.format(Locale.ROOT, "%.1f dB/s", v)));

        Collapsible lim = stage(s, card, "Limiter", "limiter", () -> mic.limiter.enabled
                ? String.format(Locale.ROOT, "Ceiling %.1f dB · reducing %.1f dB", mic.limiter.ceilingDb, chain.limiter().gainReductionDb())
                : "Off", () -> mic.limiter.enabled, v -> mic.limiter.enabled = v);
        lim.body().add(new Widgets.Meter("Gain reduction", () -> grReading(chain.limiter().gainReductionDb()), false));
        lim.body().add(slider("Ceiling", -12, -0.1, 0.1, () -> mic.limiter.ceilingDb, v -> mic.limiter.ceilingDb = v, s)
                .format(v -> String.format(Locale.ROOT, "%.1f dBFS", v)).tooltip("Nothing leaves the chain louder than this."));
        lim.body().add(slider("Release", 5, 1000, 5, () -> mic.limiter.releaseMs, v -> mic.limiter.releaseMs = v, s).format(AudioPage::ms));

        card.add(slider("Output gain", -24, 24, 0.5, () -> mic.outputGainDb, v -> mic.outputGainDb = v, s)
                .format(v -> String.format(Locale.ROOT, "%+.1f dB", v)).defaultValue(0));
        Layouts.Row reset = card.add(new Layouts.Row(Theme.SPACE_3));
        reset.add(Layouts.spacer(0), -1);
        reset.add(Button.of("Reset whole chain", () -> s.screen().confirm("Reset the processing chain?",
                "Every stage returns to the Streaming preset.", "Reset", true, () -> {
                    MicrophonePresets.resetChain(mic);
                    s.micChanged();
                    s.screen().refresh();
                })).variant(Button.Variant.GHOST), 120);
    }

    /** Gain reduction drawn on a meter: 0 dB reduction is full scale, deeper reduction shrinks it. */
    private static dev.streamable.audio.dsp.LevelMeter.Reading grReading(double reductionDb) {
        double level = -Math.abs(reductionDb);
        return new dev.streamable.audio.dsp.LevelMeter.Reading(level, level, level, false, 0);
    }

    private static void eqBand(Studio s, Collapsible eq, EqBand band, int index) {
        MicrophoneSettings mic = s.client().config().microphone;
        Layouts.Grid row = eq.body().add(new Layouts.Grid(120, Theme.SPACE_4));
        Layouts.Row head = row.add(new Layouts.Row(Theme.SPACE_3));
        head.add(new Toggle(() -> "Band " + (index + 1), () -> band.enabled, v -> {
            band.enabled = v;
            mic.eq.preset = "Custom";
            s.micChanged();
        }), -1);
        head.add(IconButton.of(Icons.Icon.CLOSE, "Remove band " + (index + 1), () -> {
            mic.eq.bands.remove(band);
            mic.eq.preset = "Custom";
            s.micChanged();
            s.screen().refresh();
        }), 16);
        row.add(s.enumDropdown(null, MicrophoneSettings.BandType.values(), t -> switch (t) {
            case LOW_SHELF -> "Low shelf";
            case BELL -> "Bell";
            case HIGH_SHELF -> "High shelf";
            case LOW_PASS -> "Low-pass";
        }, () -> band.type, t -> {
            band.type = t;
            mic.eq.preset = "Custom";
            s.micChanged();
        }));
        row.add(slider("Freq", 20, 20000, 1, () -> band.frequencyHz, v -> {
            band.frequencyHz = v;
            mic.eq.preset = "Custom";
        }, s).format(v -> v >= 1000 ? String.format(Locale.ROOT, "%.1f kHz", v / 1000) : Math.round(v) + " Hz"));
        row.add(slider("Gain", -18, 18, 0.5, () -> band.gainDb, v -> {
            band.gainDb = v;
            mic.eq.preset = "Custom";
        }, s).format(v -> String.format(Locale.ROOT, "%+.1f dB", v)).defaultValue(0)
                .enabledWhen(() -> band.type != MicrophoneSettings.BandType.LOW_PASS));
        row.add(slider("Q", 0.1, 12, 0.05, () -> band.q, v -> {
            band.q = v;
            mic.eq.preset = "Custom";
        }, s).format(v -> String.format(Locale.ROOT, "%.2f", v)));
    }

    // ---- presets, diagnostics, controls ----------------------------------------------------------

    private static void presetsCard(Studio s, Widgets.Card card) {
        MicrophoneSettings mic = s.client().config().microphone;
        card.add(new Widgets.SectionHeader("Presets", () -> mic.customPresets.size() + " saved"));
        Layouts.Row row = card.add(new Layouts.Row(Theme.SPACE_3));
        row.add(Button.of("Save as preset", () -> {
            TextField name = new TextField("Name", () -> "", v -> { }).maxLength(40);
            s.screen().openPopup(Dialog.withContent("Save preset", "Saves the whole chain under a name.", name,
                    "Save", () -> {
                        try {
                            MicrophonePresets.saveCustom(name.text().strip(), mic);
                            mic.preset = name.text().strip();
                            s.changed();
                            s.toast("Preset saved.");
                            s.screen().refresh();
                        } catch (IllegalArgumentException e) {
                            s.error(e.getMessage());
                        }
                    }));
        }).icon(Icons.Icon.PLUS), 110);
        row.add(Button.of("Copy as text", () -> s.copyToClipboard(MicrophonePresets.exportChain(mic),
                "Chain copied to the clipboard.")).icon(Icons.Icon.COPY)
                .tooltip("Share your settings: the chain as JSON, with no device names or personal data."), 96);
        row.add(Button.of("Paste from clipboard", () -> {
            String text = Minecraft.getInstance().keyboardHandler.getClipboard();
            try {
                MicrophonePresets.importChain(text, new MicrophoneSettings());
            } catch (RuntimeException e) {
                s.error("The clipboard does not contain a Stream-able chain.");
                return;
            }
            s.screen().confirm("Replace the chain?", "Applies the chain from the clipboard.", "Apply", false, () -> {
                MicrophonePresets.importChain(text, mic);
                mic.validate();
                s.micChanged();
                s.screen().refresh();
            });
        }), 130);
        row.add(Layouts.spacer(0), -1);
        for (MicrophoneSettings.CustomPreset custom : mic.customPresets) {
            Layouts.Row entry = card.add(new Layouts.Row(Theme.SPACE_3));
            entry.add(new Label(() -> custom.name), -1);
            entry.add(Button.of("Apply", () -> {
                MicrophonePresets.applyChain(custom.name, mic);
                s.micChanged();
                s.screen().refresh();
            }).variant(Button.Variant.GHOST), 56);
            entry.add(IconButton.of(Icons.Icon.CLOSE, "Delete this preset", () -> s.screen().confirm(
                    "Delete \"" + custom.name + "\"?", null, "Delete", true, () -> {
                        MicrophonePresets.deleteCustom(custom.name, mic);
                        s.changed();
                        s.screen().refresh();
                    })), 16);
        }
    }

    private static void diagnosticsCard(Studio s, Widgets.Card card) {
        StreamAbleClient client = s.client();
        card.add(new Widgets.SectionHeader("Diagnostics", () -> "Real-time health of the processing worker"));
        Layouts.Grid grid = card.add(new Layouts.Grid(96, Theme.SPACE_4));
        var processor = client.microphone().processor();
        grid.add(new Widgets.MetricCard("DSP time", () -> String.format(Locale.ROOT, "%.2f ms",
                processor.stats().dspMillis()), () -> processor.stats().realTimeFactor() > 0.7 ? Theme.WARNING : Theme.TEXT,
                () -> String.format(Locale.ROOT, "peak %.2f ms per 10 ms", processor.stats().dspPeakMillis())));
        grid.add(new Widgets.MetricCard("Latency", () -> String.format(Locale.ROOT, "%.1f ms",
                processor.stats().chainLatencyMillis()), () -> Theme.TEXT, () -> "whole chain"));
        grid.add(new Widgets.MetricCard("Backlog", () -> processor.stats().backlogBlocks() + " blocks",
                () -> processor.stats().backlogBlocks() > 8 ? Theme.WARNING : Theme.TEXT, () -> "10 ms each"));
        grid.add(new Widgets.MetricCard("Dropped", () -> Long.toString(processor.stats().droppedBlocks()),
                () -> processor.stats().droppedBlocks() > 0 ? Theme.DANGER : Theme.SUCCESS,
                () -> processor.stats().overrunEvents() + " overruns"));
        grid.add(new Widgets.MetricCard("Processed", () -> Long.toString(processor.stats().processedBlocks()),
                () -> Theme.TEXT, () -> processor.stats().underruns() + " underruns"));
        card.add(new Widgets.Notice(() -> processor.stats().overloaded()
                ? "Processing is falling behind real time, so AI noise cancellation is being skipped until it "
                + "catches up. Choose a lighter noise model or a lower strength." : null, () -> Theme.WARNING));
    }

    private static void controlsCard(Studio s, Widgets.Card card) {
        MicrophoneSettings mic = s.client().config().microphone;
        card.add(new Widgets.SectionHeader("Mute and push-to-talk",
                () -> "Keys are set in Options > Controls > Key Binds > Stream-able."));
        Layouts.Grid grid = card.add(new Layouts.Grid(170, Theme.SPACE_5));
        grid.add(Toggle.of("Muted", () -> mic.muted, v -> {
            mic.muted = v;
            s.micChanged();
        }));
        grid.add(Toggle.of("Push-to-talk", () -> mic.pushToTalk, v -> {
            mic.pushToTalk = v;
            if (v) {
                mic.pushToMute = false;
            }
            s.micChanged();
        }).detail(() -> "Only transmit while the key is held."));
        grid.add(Toggle.of("Push-to-mute", () -> mic.pushToMute, v -> {
            mic.pushToMute = v;
            if (v) {
                mic.pushToTalk = false;
            }
            s.micChanged();
        }).detail(() -> "Silence while the key is held."));
        card.add(slider("Push-to-talk release delay", 0, 2000, 10, () -> mic.pushToTalkReleaseMs,
                v -> mic.pushToTalkReleaseMs = v, s).format(AudioPage::ms).visibleWhen(() -> mic.pushToTalk));
    }
}
