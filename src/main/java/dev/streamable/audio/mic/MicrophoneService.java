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
    private SincResampler plasmoResampler;
    private int plasmoRate;
    private final ChannelSelector plasmoChannels = new ChannelSelector();
    private float[] plasmoMono = new float[4096];
    private float[] plasmoResampled = new float[8192];

    public MicrophoneService(AudioMixer mixer, MicrophoneSettings settings, RuntimeManager runtimes) {
        this.settings = settings;
        this.noise = new NoiseCancellationManager(runtimes, noiseStage);
        this.processor = new MicrophoneProcessor(mixer, settings, noise);
        this.input = new MicrophoneInput(processor, devices);
        this.test = new MicrophoneTest(processor);
        this.monitor = new MicrophoneMonitor(processor);
        this.calibration = new MicrophoneCalibration(processor);
        MicrophoneRouting.setSink(this::acceptPlasmo);
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
        boolean plasmo = settings.source == MicrophoneSettings.Source.PLASMO_VOICE;
        processor.chain().setForcedBypass(plasmo && !settings.processPlasmoVoice);
        noise.apply(settings);
        if (wanted && !processor.isRunning()) {
            processor.start();
        }
        if (wanted && !plasmo && !input.isRunning()) {
            input.startAsync(settings);
        }
        if ((!wanted || plasmo) && input.isRunning()) {
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

    /** Plasmo Voice's processed microphone, routed through our chain when selected. */
    private boolean acceptPlasmo(short[] samples, int channels, int sampleRate) {
        if (settings.source != MicrophoneSettings.Source.PLASMO_VOICE || !processor.isRunning()) {
            return false;
        }
        synchronized (this) {
            int frames = samples.length / Math.max(1, channels);
            if (plasmoMono.length < frames) {
                plasmoMono = new float[frames];
            }
            int n = plasmoChannels.toMono(samples, frames, channels, MicrophoneSettings.InputChannel.MIX, plasmoMono);
            if (sampleRate == 48_000) {
                processor.submit(plasmoMono, 0, n);
            } else {
                if (plasmoResampler == null || plasmoRate != sampleRate) {
                    plasmoResampler = new SincResampler(sampleRate, 48_000);
                    plasmoRate = sampleRate;
                }
                int needed = plasmoResampler.maxOutput(n) + 4;
                if (plasmoResampled.length < needed) {
                    plasmoResampled = new float[needed];
                }
                int out = plasmoResampler.process(plasmoMono, 0, n, plasmoResampled);
                processor.submit(plasmoResampled, 0, out);
            }
        }
        return true;
    }

    /** A plain-language warning when stacking processing on Plasmo Voice, or {@code null}. */
    public String plasmoWarning() {
        if (settings.source == MicrophoneSettings.Source.PLASMO_VOICE && settings.processPlasmoVoice
                && settings.noise.level.ordinal() >= MicrophoneSettings.NoiseLevel.BALANCED.ordinal()) {
            return "This source may already include Plasmo Voice noise processing. Stacking aggressive "
                    + "suppression can reduce voice quality.";
        }
        return null;
    }

    public String status() {
        if (settings.source == MicrophoneSettings.Source.PLASMO_VOICE) {
            return processor.isRunning() ? "Using the Plasmo Voice microphone." : "Not capturing.";
        }
        return input.status();
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
