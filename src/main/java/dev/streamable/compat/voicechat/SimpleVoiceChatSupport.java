package dev.streamable.compat.voicechat;

import dev.streamable.audio.AudioBus;
import dev.streamable.audio.AudioMixer;
import dev.streamable.audio.VoicePcmConverter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State and audio plumbing for the Simple Voice Chat integration.
 *
 * <p>This class never references a Simple Voice Chat type, so it is safe to
 * load whether or not the mod is installed. The API-facing half is
 * {@link SimpleVoiceChatPlugin}, which Simple Voice Chat itself instantiates
 * through its {@code voicechat} Fabric entrypoint - so without the mod that
 * class is never loaded at all.</p>
 *
 * <p>Simple Voice Chat plays voices through its own OpenAL context, which the
 * game-audio loopback does not see. Its client API hands out every received
 * voice packet (48 kHz mono, 20 ms) and the local microphone as it is about to
 * be sent; both are observed only, never modified.</p>
 */
public final class SimpleVoiceChatSupport {

    /** Simple Voice Chat's mod id. */
    public static final String MOD_ID = "voicechat";
    /** Simple Voice Chat's fixed working format. */
    static final int SAMPLE_RATE = 48_000;

    private static volatile AudioMixer mixer;
    private static volatile boolean captureIncoming = true;
    /** Off until the user selects Simple Voice Chat as the microphone source. */
    private static volatile boolean captureMicrophone;
    private static volatile boolean pluginLoaded;
    private static volatile boolean connected;

    /** Player positions sampled on the client thread, read from Simple Voice Chat's audio thread. */
    private static final Map<UUID, double[]> positions = new ConcurrentHashMap<>();
    private static volatile double[] listener;

    private SimpleVoiceChatSupport() {
    }

    public static boolean isInstalled() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    /** Whether Simple Voice Chat loaded Stream-able's plugin. */
    public static boolean isActive() {
        return pluginLoaded;
    }

    public static void attach(AudioMixer audioMixer) {
        mixer = audioMixer;
    }

    public static void configure(boolean incoming, boolean microphone) {
        captureIncoming = incoming;
        captureMicrophone = microphone;
    }

    static void markLoaded() {
        pluginLoaded = true;
    }

    static void setConnected(boolean value) {
        connected = value;
    }

    static boolean capturingMicrophone() {
        return captureMicrophone;
    }

    /** Samples player positions for distance attenuation. Client thread, once per tick. */
    public static void tick(Minecraft client) {
        if (!pluginLoaded) {
            return;
        }
        if (client.level == null || client.player == null) {
            positions.clear();
            listener = null;
            return;
        }
        listener = new double[]{client.player.getX(), client.player.getEyeY(), client.player.getZ()};
        positions.keySet().retainAll(client.level.players().stream().map(Player::getUUID).toList());
        for (Player player : client.level.players()) {
            positions.put(player.getUUID(), new double[]{player.getX(), player.getEyeY(), player.getZ()});
        }
    }

    /**
     * Gain for a voice at {@code distance} blocks with the given voice range:
     * full volume up close, fading linearly to silence at the range - an
     * approximation of what the player hears, since the mod's own spatial
     * rendering happens in its private OpenAL context.
     */
    static float distanceGain(double distance, double range) {
        if (!(range > 0) || !Double.isFinite(distance)) {
            return 1f;
        }
        return (float) Math.clamp(1.0 - distance / range, 0.0, 1.0);
    }

    static float gainForEntity(UUID entity, float range) {
        double[] from = listener;
        double[] to = positions.get(entity);
        if (from == null || to == null) {
            return 1f;   // unknown position (e.g. out of render distance): do not guess
        }
        return distanceGain(Math.sqrt(sq(from[0] - to[0]) + sq(from[1] - to[1]) + sq(from[2] - to[2])), range);
    }

    static float gainForPosition(double x, double y, double z, float range) {
        double[] from = listener;
        if (from == null) {
            return 1f;
        }
        return distanceGain(Math.sqrt(sq(from[0] - x) + sq(from[1] - y) + sq(from[2] - z)), range);
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Another player's voice packet, keyed by its channel so simultaneous speakers are summed. */
    static void incoming(UUID channel, short[] samples, float gain) {
        AudioMixer current = mixer;
        if (!captureIncoming || current == null || !current.isActive() || samples == null || samples.length == 0
                || gain <= 0f) {
            return;
        }
        short[] scaled = gain >= 0.999f ? samples : scale(samples, gain);
        byte[] pcm = VoicePcmConverter.toMixerFormat(scaled, 1, SAMPLE_RATE);
        if (pcm.length > 0) {
            current.submit(AudioBus.Kind.VOICE_CHAT, channel, pcm, pcm.length);
        }
    }

    static short[] scale(short[] samples, float gain) {
        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            out[i] = (short) Math.clamp(Math.round(samples[i] * gain), Short.MIN_VALUE, Short.MAX_VALUE);
        }
        return out;
    }

    /** Short status line for the Audio page. */
    public static String statusLine() {
        if (!isInstalled()) {
            return "Simple Voice Chat is not installed.";
        }
        if (!pluginLoaded) {
            return "Simple Voice Chat found; waiting for it to load Stream-able's plugin.";
        }
        return connected
                ? "Simple Voice Chat connected - other players' voices are captured."
                : "Simple Voice Chat ready - not connected to a voice server.";
    }
}
