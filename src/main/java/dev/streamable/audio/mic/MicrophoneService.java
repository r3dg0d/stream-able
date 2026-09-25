package dev.streamable.audio.mic;

import dev.streamable.StreamAbleLog;
import dev.streamable.audio.AudioMixer;
import dev.streamable.audio.MicrophoneRouting;
import dev.streamable.audio.ai.NoiseCancellationManager;
import dev.streamable.audio.ai.NoiseCancellationStage;
import dev.streamable.audio.dsp.SincResampler;
import dev.streamable.config.MicrophoneSettings;
import dev.streamable.runtime.RuntimeManager;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The microphone subsystem as the rest of the mod sees it.
 *
 * <p>Capture runs while anything needs it - a recording, a stream, or the
 * Audio page (for meters, calibration and the test) - and audio reaches the
 * program mix only while a recording or stream is active. Recording and
 * streaming receive the same processed signal through the program mixer.</p>
 *
 * <p>Privacy: every step - capture, DSP, AI inference, test clips - runs on
 * this computer. Model files are downloaded; microphone audio is never sent
 * anywhere.</p>
 */
public final class MicrophoneService implements AutoCloseable {

    public enum User { RECORDING, STREAMING, STUDIO }

    private final MicrophoneSettings settings;
    private final MicrophoneDevices devices = new MicrophoneDevices();
    private final NoiseCancellationStage noiseStage = new NoiseCancellationStage();
    private final NoiseCancellationManager noise;
    private final MicrophoneProcessor processor;
    private final MicrophoneInput input;
    private final MicrophoneTest test;
    private final MicrophoneMonitor monitor;
    private final MicrophoneCalibration calibration;
    private final Set<User> users = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled = true;
    private SincResampler voiceResampler;
    private int voiceRate;
    private final ChannelSelector voiceChannels = new ChannelSelector();
    private float[] voiceMono = new float[4096];
    private float[] voiceResampled = new float[8192];

    public MicrophoneService(AudioMixer mixer, MicrophoneSettings settings, RuntimeManager runtimes) {
        this.settings = settings;
        this.noise = new NoiseCancellationManager(runtimes, noiseStage);
        this.processor = new MicrophoneProcessor(mixer, settings, noise);
        this.input = new MicrophoneInput(processor, devices);
        this.test = new MicrophoneTest(processor);
        this.monitor = new MicrophoneMonitor(processor);
        this.calibration = new MicrophoneCalibration(processor);
        MicrophoneRouting.setSink(this::acceptVoiceChat);
        devices.refreshAsync();
    }

    public MicrophoneSettings settings() {
        return settings;
    }

    public MicrophoneDevices devices() {
        return devices;
    }

    public MicrophoneProcessor processor() {
        return processor;
    }

    public MicrophoneInput input() {
        return input;
    }

    public NoiseCancellationManager noise() {
        return noise;
    }

    public MicrophoneTest test() {
        return test;
    }

    public MicrophoneMonitor monitor() {
        return monitor;
    }

    public MicrophoneCalibration calibration() {
        return calibration;
    }

    /** "Capture microphone" preference; when off nothing is captured for outputs. */
    public void setEnabled(boolean value) {
        enabled = value;
        reconcile();
    }

    public void acquire(User user) {
        users.add(user);
        reconcile();
    }

    public void release(User user) {
        users.remove(user);
        reconcile();
    }

    public boolean isCapturing() {
        return processor.isRunning();
    }

    /** Re-applies everything after a settings change. Cheap when nothing changed. */
    public synchronized void reconcile() {
        boolean forOutputs = enabled && (users.contains(User.RECORDING) || users.contains(User.STREAMING));
        boolean wanted = forOutputs || users.contains(User.STUDIO);
        processor.setSendToMixer(forOutputs);
        boolean external = effectiveSource() != MicrophoneSettings.Source.SYSTEM;
        processor.chain().setForcedBypass(external && !settings.processPlasmoVoice);
        noise.apply(settings);
        if (wanted && !processor.isRunning()) {
            processor.start();
        }
        if (wanted && !external && !input.isRunning()) {
            input.startAsync(settings);
        }
        if ((!wanted || external) && input.isRunning()) {
            input.stop();
        }
        if (!wanted && processor.isRunning()) {
            monitor.stop();
            test.stopRecording();
            calibration.cancel();
            processor.stop();
        }
        if (settings.monitoring && wanted) {
            monitor.configure(settings.monitorProcessed, settings.monitorVolume);
            monitor.start();
        } else {
            monitor.stop();
        }
    }

    /** Restarts capture, e.g. after the device changed. */
    public synchronized void restartInput() {
        input.stop();
        reconcile();
    }

    /**
     * The microphone source actually in use: a voice-chat mod that is selected
     * but not installed falls back to the system device, so the microphone never
     * silently disappears.
     */
    public MicrophoneSettings.Source effectiveSource() {
        return switch (settings.source) {
            case SYSTEM -> MicrophoneSettings.Source.SYSTEM;
            case PLASMO_VOICE -> dev.streamable.compat.plasmovoice.PlasmoVoiceSupport.isInstalled()
                    ? MicrophoneSettings.Source.PLASMO_VOICE : MicrophoneSettings.Source.SYSTEM;
            case SIMPLE_VOICE_CHAT -> dev.streamable.compat.voicechat.SimpleVoiceChatSupport.isInstalled()
                    ? MicrophoneSettings.Source.SIMPLE_VOICE_CHAT : MicrophoneSettings.Source.SYSTEM;
        };
    }

    /** A voice-chat mod's microphone, routed through our chain when it is the selected source. */
    private boolean acceptVoiceChat(MicrophoneSettings.Source origin, short[] samples, int channels, int sampleRate) {
        if (origin == MicrophoneSettings.Source.SYSTEM || effectiveSource() != origin || !processor.isRunning()) {
            return false;
        }
        synchronized (this) {
            int frames = samples.length / Math.max(1, channels);
            if (voiceMono.length < frames) {
                voiceMono = new float[frames];
            }
            int n = voiceChannels.toMono(samples, frames, channels, MicrophoneSettings.InputChannel.MIX, voiceMono);
            if (sampleRate == 48_000) {
                processor.submit(voiceMono, 0, n);
            } else {
                if (voiceResampler == null || voiceRate != sampleRate) {
                    voiceResampler = new SincResampler(sampleRate, 48_000);
                    voiceRate = sampleRate;
                }
                int needed = voiceResampler.maxOutput(n) + 4;
                if (voiceResampled.length < needed) {
                    voiceResampled = new float[needed];
                }
                int out = voiceResampler.process(voiceMono, 0, n, voiceResampled);
                processor.submit(voiceResampled, 0, out);
            }
        }
        return true;
    }

    /** A plain-language warning when stacking processing on a voice-chat mod's own, or {@code null}. */
    public String plasmoWarning() {
        MicrophoneSettings.Source source = effectiveSource();
        if (source != MicrophoneSettings.Source.SYSTEM && settings.processPlasmoVoice
                && settings.noise.level.ordinal() >= MicrophoneSettings.NoiseLevel.BALANCED.ordinal()) {
            return "This source may already include " + voiceChatName(source) + " noise processing. Stacking "
                    + "aggressive suppression can reduce voice quality.";
        }
        return null;
    }

    public String status() {
        MicrophoneSettings.Source source = effectiveSource();
        if (source != settings.source) {
            return voiceChatName(settings.source) + " is not installed; using the microphone device. "
                    + input.status();
        }
        if (source != MicrophoneSettings.Source.SYSTEM) {
            return processor.isRunning() ? "Using the " + voiceChatName(source) + " microphone (only while you "
                    + "transmit)." : "Not capturing.";
        }
        return input.status();
    }

    static String voiceChatName(MicrophoneSettings.Source source) {
        return switch (source) {
            case PLASMO_VOICE -> "Plasmo Voice";
            case SIMPLE_VOICE_CHAT -> "Simple Voice Chat";
            case SYSTEM -> "The microphone device";
        };
    }

    @Override
    public void close() {
        MicrophoneRouting.setSink(null);
        monitor.stop();
        input.stop();
        processor.stop();
        noise.close();
        StreamAbleLog.AUDIO.debug("Microphone service closed.");
    }
}
