package dev.streamable.compat.plasmovoice;

import dev.streamable.StreamAbleLog;
import dev.streamable.audio.AudioBus;
import dev.streamable.audio.AudioMixer;
import dev.streamable.audio.VoicePcmConverter;
import org.jetbrains.annotations.NotNull;
import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.AddonLoaderScope;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.client.PlasmoVoiceClient;
import su.plo.voice.api.client.audio.device.AudioDevice;
import su.plo.voice.api.client.event.audio.capture.AudioCaptureProcessedEvent;
import su.plo.voice.api.client.event.audio.source.AudioSourceWriteEvent;
import su.plo.voice.api.event.EventSubscribe;

import javax.sound.sampled.AudioFormat;

/**
 * Captures Plasmo Voice audio into Stream-able's program mix.
 *
 * <h2>Why this is needed</h2>
 * <p>Stream-able captures game audio by replacing Minecraft's OpenAL device with
 * a loopback device. Plasmo Voice does not play through that device - it opens
 * its <em>own</em> OpenAL context ({@code AlContextOutputDevice}) - so proximity
 * chat is completely absent from recordings and streams unless it is captured
 * explicitly. That is what this addon does.</p>
 *
 * <h2>What is captured</h2>
 * <ul>
 *   <li>{@link AudioSourceWriteEvent} - decoded audio from every other player,
 *       written into the {@link AudioBus.Kind#VOICE_CHAT} bus.</li>
 *   <li>{@link AudioCaptureProcessedEvent} - the local microphone <em>after</em>
 *       Plasmo Voice has applied its own processing (noise suppression, gain,
 *       activation gating), written into {@link AudioBus.Kind#MICROPHONE}. Using
 *       Plasmo Voice's processed signal means the stream hears exactly what other
 *       players hear, and it only carries audio while the player is actually
 *       transmitting rather than an always-open mic.</li>
 * </ul>
 *
 * <p>Both are observation only: neither event is cancelled or modified, so voice
 * chat behaves exactly as it would without Stream-able installed.</p>
 *
 * <h2>Loading</h2>
 * <p>Registered programmatically through {@link PlasmoVoiceClient#getAddonsLoader()}
 * rather than through a Fabric entrypoint, so this class - and every Plasmo Voice
 * type it references - is only loaded when the {@code plasmovoice} mod is
 * actually present. See {@link PlasmoVoiceSupport}.</p>
 */
@Addon(
        id = "pv-addon-streamable",
        name = "Stream-able",
        scope = AddonLoaderScope.CLIENT,
        version = "1.0.0",
        authors = {"Stream-able contributors"}
)
public final class PlasmoVoiceCompat implements AddonInitializer {

    /** Plasmo Voice's own working rate; used until a device reports otherwise. */
    private static final int ASSUMED_SAMPLE_RATE = 48_000;

    private final AudioMixer mixer;
    private volatile boolean captureIncoming = true;
    /**
     * Off until the user asks for it. Broadcasting someone's microphone because
     * a voice-chat integration happened to be available would be a genuinely
     * bad surprise, so this tracks the microphone setting specifically - not the
     * voice-chat one.
     */
    private volatile boolean captureMicrophone;

    @InjectPlasmoVoice
    private PlasmoVoiceClient voiceClient;

    public PlasmoVoiceCompat(AudioMixer mixer) {
        this.mixer = mixer;
    }

    @Override
    public void onAddonInitialize() {
        StreamAbleLog.AUDIO.info(
                "Plasmo Voice integration active - proximity chat will be captured into recordings and streams.");
    }

    @Override
    public void onAddonShutdown() {
        StreamAbleLog.AUDIO.debug("Plasmo Voice integration shut down.");
    }

    /** Enables or disables capture without unloading the addon. */
    public void setCaptureIncoming(boolean value) {
        this.captureIncoming = value;
    }

    public void setCaptureMicrophone(boolean value) {
        this.captureMicrophone = value;
    }

    /**
     * Whether a voice server is actually connected.
     *
     * <p>False in singleplayer and on servers without Plasmo Voice, where no
     * voice events will ever fire. Used to explain silence rather than let it
     * look like a fault.</p>
     */
    public boolean isConnected() {
        PlasmoVoiceClient client = voiceClient;
        try {
            return client != null && client.getServerConnection().isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Another player's decoded voice.
     *
     * <p>Fires once per audio packet per speaking player; the mixer sums them,
     * so several people talking at once arrive as several calls.</p>
     */
    @EventSubscribe
    public void onAudioSourceWrite(@NotNull AudioSourceWriteEvent event) {
        if (!captureIncoming || !mixer.isActive()) {
            return;
        }
        try {
            short[] samples = event.getSamples();
            if (samples == null || samples.length == 0) {
                return;
            }
            submit(AudioBus.Kind.VOICE_CHAT, samples, outputChannels(), outputSampleRate());
        } catch (RuntimeException e) {
            // Never let a capture problem disturb voice playback.
            StreamAbleLog.AUDIO.debug("Could not capture incoming voice audio: {}", e.toString());
        }
    }

    /** The local microphone, already processed by Plasmo Voice. */
    @EventSubscribe
    public void onAudioCaptureProcessed(@NotNull AudioCaptureProcessedEvent event) {
        if (!captureMicrophone || !mixer.isActive()) {
            return;
        }
        try {
            // Ask for stereo so no channel conversion guesswork is needed.
            short[] samples = event.getProcessed().getSamples(true);
            if (samples == null || samples.length == 0) {
                return;
            }
            // Through Stream-able's microphone chain when it is handling this
            // source; directly to the bus otherwise.
            if (!dev.streamable.audio.MicrophoneRouting.route(samples, 2, inputSampleRate(event))) {
                submit(AudioBus.Kind.MICROPHONE, samples, 2, inputSampleRate(event));
            }
        } catch (RuntimeException e) {
            StreamAbleLog.AUDIO.debug("Could not capture microphone audio: {}", e.toString());
        }
    }

    private void submit(AudioBus.Kind bus, short[] samples, int channels, int sampleRate) {
        byte[] pcm = VoicePcmConverter.toMixerFormat(samples, channels, sampleRate);
        if (pcm.length > 0) {
            mixer.submit(bus, pcm, pcm.length);
        }
    }

    private int outputChannels() {
        AudioFormat format = outputFormat();
        return format == null ? 1 : Math.clamp(format.getChannels(), 1, 2);
    }

    private int outputSampleRate() {
        AudioFormat format = outputFormat();
        if (format == null || format.getSampleRate() <= 0) {
            return ASSUMED_SAMPLE_RATE;
        }
        return (int) format.getSampleRate();
    }

    private AudioFormat outputFormat() {
        PlasmoVoiceClient client = voiceClient;
        if (client == null) {
            return null;
        }
        return client.getDeviceManager().getOutputDevice()
                .map(AudioDevice::getFormat)
                .orElse(null);
    }

    private int inputSampleRate(AudioCaptureProcessedEvent event) {
        try {
            AudioFormat format = event.getDevice() == null ? null : event.getDevice().getFormat();
            if (format != null && format.getSampleRate() > 0) {
                return (int) format.getSampleRate();
            }
        } catch (RuntimeException e) {
            StreamAbleLog.AUDIO.debug("Could not read the input device format: {}", e.toString());
        }
        return ASSUMED_SAMPLE_RATE;
    }
}
