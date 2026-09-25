package dev.streamable.compat.voicechat;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import dev.streamable.StreamAbleLog;
import dev.streamable.audio.AudioBus;
import dev.streamable.audio.MicrophoneRouting;
import dev.streamable.audio.VoicePcmConverter;
import dev.streamable.config.MicrophoneSettings;

/**
 * Stream-able's Simple Voice Chat plugin. Loaded only by Simple Voice Chat,
 * through the {@code voicechat} entrypoint in {@code fabric.mod.json}.
 *
 * <p>Every handler observes and returns; none cancels an event or changes its
 * audio, so voice chat behaves exactly as it would without Stream-able.
 * Handlers run on Simple Voice Chat's audio threads and never block.</p>
 */
public final class SimpleVoiceChatPlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "stream-able";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        // Simple Voice Chat dispatches each kind of received sound under its own
        // interface, so all three are registered.
        registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, this::onEntitySound);
        registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, this::onLocationalSound);
        registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, this::onStaticSound);
        registration.registerEvent(ClientSoundEvent.class, this::onMicrophone);
        registration.registerEvent(ClientVoicechatConnectionEvent.class,
                event -> SimpleVoiceChatSupport.setConnected(event.isConnected()));
        SimpleVoiceChatSupport.markLoaded();
        StreamAbleLog.AUDIO.info("Simple Voice Chat integration active - voice chat can be captured into "
                + "recordings and streams.");
    }

    private void onEntitySound(ClientReceiveSoundEvent.EntitySound event) {
        try {
            SimpleVoiceChatSupport.incoming(event.getId(), event.getRawAudio(),
                    SimpleVoiceChatSupport.gainForEntity(event.getEntityId(), event.getDistance()));
        } catch (RuntimeException e) {
            StreamAbleLog.AUDIO.debug("Could not capture a Simple Voice Chat voice: {}", e.toString());
        }
    }

    private void onLocationalSound(ClientReceiveSoundEvent.LocationalSound event) {
        try {
            Position p = event.getPosition();
            float gain = p == null ? 1f
                    : SimpleVoiceChatSupport.gainForPosition(p.getX(), p.getY(), p.getZ(), event.getDistance());
            SimpleVoiceChatSupport.incoming(event.getId(), event.getRawAudio(), gain);
        } catch (RuntimeException e) {
            StreamAbleLog.AUDIO.debug("Could not capture a Simple Voice Chat sound: {}", e.toString());
        }
    }

    /** Group and other non-spatial audio: heard at full volume. */
    private void onStaticSound(ClientReceiveSoundEvent.StaticSound event) {
        try {
            SimpleVoiceChatSupport.incoming(event.getId(), event.getRawAudio(), 1f);
        } catch (RuntimeException e) {
            StreamAbleLog.AUDIO.debug("Could not capture a Simple Voice Chat sound: {}", e.toString());
        }
    }

    /**
     * The local microphone as Simple Voice Chat is about to send it - after its
     * own processing, and only while the player is transmitting.
     */
    private void onMicrophone(ClientSoundEvent event) {
        if (!SimpleVoiceChatSupport.capturingMicrophone()) {
            return;
        }
        try {
            short[] samples = event.getRawAudio();
            if (samples == null || samples.length == 0) {
                return;
            }
            if (!MicrophoneRouting.route(MicrophoneSettings.Source.SIMPLE_VOICE_CHAT, samples, 1,
                    SimpleVoiceChatSupport.SAMPLE_RATE)) {
                // Stream-able's chain is not running: straight to the bus.
                var mixer = dev.streamable.StreamAbleClient.get() == null ? null
                        : dev.streamable.StreamAbleClient.get().audioMixer();
                if (mixer != null && mixer.isActive()) {
                    byte[] pcm = VoicePcmConverter.toMixerFormat(samples, 1, SimpleVoiceChatSupport.SAMPLE_RATE);
                    mixer.submit(AudioBus.Kind.MICROPHONE, pcm, pcm.length);
                }
            }
        } catch (RuntimeException e) {
            StreamAbleLog.AUDIO.debug("Could not capture the Simple Voice Chat microphone: {}", e.toString());
        }
    }
}
