package dev.streamable.compat.plasmovoice;

import dev.streamable.StreamAbleLog;
import dev.streamable.audio.AudioMixer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Optional entry point for the Plasmo Voice integration.
 *
 * <p>Every reference to a Plasmo Voice class lives in {@link PlasmoVoiceCompat},
 * which this class only touches after confirming the mod is installed. Without
 * that separation, loading Stream-able on a pack that has no voice chat would
 * fail with {@code NoClassDefFoundError} - the same discipline used for MCEF.</p>
 */
public final class PlasmoVoiceSupport {

    /** The Plasmo Voice mod id. */
    public static final String MOD_ID = "plasmovoice";

    private static Object addon;
    private static int registrationAttempts;
    /** Plasmo Voice may initialise after us; give it a bounded number of ticks. */
    private static final int MAX_REGISTRATION_ATTEMPTS = 200;

    private PlasmoVoiceSupport() {
    }

    public static boolean isInstalled() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    /** Whether voice capture is actually running. */
    public static boolean isActive() {
        return addon != null;
    }

    /**
     * Registers the voice-capture addon if Plasmo Voice is present.
     *
     * <p>Fabric does not order client entrypoints, so Plasmo Voice's own
     * initialiser may not have run when Stream-able starts. A single attempt at
     * startup would therefore fail on some launches and silently never capture
     * voice again, so this is safe to call repeatedly and is retried from the
     * client tick until it succeeds or the attempt budget runs out.</p>
     *
     * @return {@code true} when the integration is active
     */
    public static boolean register(AudioMixer mixer) {
        if (addon != null) {
            return true;
        }
        if (!isInstalled() || registrationAttempts >= MAX_REGISTRATION_ATTEMPTS) {
            return false;
        }
        registrationAttempts++;
        try {
            PlasmoVoiceCompat compat = new PlasmoVoiceCompat(mixer);
            su.plo.voice.api.client.PlasmoVoiceClient.getAddonsLoader().load(compat);
            addon = compat;
            StreamAbleLog.AUDIO.info("Plasmo Voice integration registered.");
            return true;
        } catch (Throwable t) {
            // Plasmo Voice not ready yet is the common case and is not an error;
            // only complain once the budget is exhausted. A LinkageError from an
            // incompatible Plasmo Voice lands here too.
            if (registrationAttempts >= MAX_REGISTRATION_ATTEMPTS) {
                StreamAbleLog.AUDIO.warn(
                        "Could not register the Plasmo Voice integration; voice chat will not be "
                                + "captured in recordings or streams.", t);
            } else {
                StreamAbleLog.AUDIO.debug("Plasmo Voice not ready yet ({}); will retry.", t.toString());
            }
            return false;
        }
    }

    /** True once registration has succeeded or been given up on. */
    public static boolean isSettled() {
        return addon != null || !isInstalled() || registrationAttempts >= MAX_REGISTRATION_ATTEMPTS;
    }

    /**
     * Applies the user's capture preferences to a running integration.
     *
     * <p>The null check comes first deliberately: the {@code instanceof} below
     * names a class that references Plasmo Voice types, and reaching it without
     * the mod installed could raise {@code NoClassDefFoundError}.</p>
     */
    public static void configure(boolean captureVoiceChat, boolean captureMicrophone) {
        if (addon == null) {
            return;
        }
        if (addon instanceof PlasmoVoiceCompat compat) {
            compat.setCaptureIncoming(captureVoiceChat);
            compat.setCaptureMicrophone(captureMicrophone);
        }
    }

    /** Short status line for the Audio settings section. */
    public static String statusLine() {
        if (!isInstalled()) {
            return "Plasmo Voice is not installed - voice chat is not captured.";
        }
        if (!isActive()) {
            return isSettled()
                    ? "Plasmo Voice found, but the integration did not start (see the log)."
                    : "Waiting for Plasmo Voice to finish starting...";
        }
        // Distinguish "nothing to capture" from "not working": in singleplayer,
        // and on servers without Plasmo Voice, there is simply no voice chat.
        return isVoiceServerConnected()
                ? "Plasmo Voice connected - proximity chat is captured."
                : "Plasmo Voice ready - no voice server connected (singleplayer has no voice chat).";
    }

    private static boolean isVoiceServerConnected() {
        return addon instanceof PlasmoVoiceCompat compat && compat.isConnected();
    }
}
