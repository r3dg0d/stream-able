package dev.streamable.mixin;

import com.mojang.blaze3d.audio.DeviceList;
import com.mojang.blaze3d.audio.Library;
import dev.streamable.StreamAbleClient;
import dev.streamable.StreamAbleLog;
import dev.streamable.audio.OpenALLoopbackCapture;
import org.lwjgl.openal.ALC10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;

/**
 * Routes Minecraft's audio through an OpenAL loopback device so game sound can
 * be captured.
 *
 * <p>OpenAL gives no way to tap a normal playback device. The technique
 * Record-able established - and this preserves - is to open an
 * {@code ALC_SOFT_loopback} device instead: Minecraft renders audio into a
 * buffer Stream-able owns, which is then both played back through Java Sound
 * and fed to the recorder and the broadcast. Without this, recordings and
 * streams are silent.</p>
 *
 * <p><b>Risk controls.</b> Replacing the game's audio device is invasive, so:</p>
 * <ul>
 *   <li>Every injection uses {@code require = 0}. If a future Minecraft build
 *       changes {@code Library.init}, the mixin quietly does nothing instead of
 *       crashing the game on startup.</li>
 *   <li>It is skipped entirely when game-audio capture is disabled in the
 *       config, falling back to normal device opening.</li>
 *   <li>Any failure falls back to the real device, so the worst case is a silent
 *       recording rather than a client with no sound.</li>
 * </ul>
 *
 * <p>Targets for Minecraft 26.1.2, where {@code openDeviceOrFallback} lives on
 * {@link Library} itself and takes a preferred plus a fallback device name -
 * both changed from the 1.21.x signature this was originally written against.</p>
 */
@Mixin(Library.class)
public abstract class AudioLibraryMixin {

    @Unique
    private static boolean streamable$usingLoopback;

    @Redirect(
            method = "init(Ljava/lang/String;Lcom/mojang/blaze3d/audio/DeviceList;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/audio/Library;openDeviceOrFallback"
                            + "(Ljava/lang/String;Ljava/lang/String;)J"),
            require = 0)
    private static long streamable$openLoopbackDevice(String preferred, String fallback) {
        streamable$usingLoopback = false;
        try {
            if (!streamable$captureWanted()) {
                return streamable$openNormalDevice(preferred, fallback);
            }
            if (!OpenALLoopbackCapture.isLoopbackSupported()) {
                StreamAbleLog.AUDIO.info(
                        "ALC_SOFT_loopback is not supported here; using the normal audio device. "
                                + "Recordings and streams will have no game audio.");
                return streamable$openNormalDevice(preferred, fallback);
            }
            long device = OpenALLoopbackCapture.getInstance().openLoopbackDevice();
            if (device == 0L) {
                StreamAbleLog.AUDIO.warn("Could not open a loopback audio device; using the normal one.");
                return streamable$openNormalDevice(preferred, fallback);
            }
            streamable$usingLoopback = true;
            StreamAbleLog.AUDIO.info("Game audio is routed through an OpenAL loopback device.");
            return device;
        } catch (Throwable t) {
            StreamAbleLog.AUDIO.error("Loopback audio setup failed; using the normal device", t);
            return streamable$openNormalDevice(preferred, fallback);
        }
    }

    @Redirect(
            method = "init(Ljava/lang/String;Lcom/mojang/blaze3d/audio/DeviceList;Z)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext"
                    + "(JLjava/nio/IntBuffer;)J"),
            require = 0)
    private long streamable$createLoopbackContext(long device, IntBuffer originalAttributes) {
        if (!streamable$usingLoopback) {
            return ALC10.alcCreateContext(device, originalAttributes);
        }
        // A loopback context must be created with an explicit output format;
        // the attributes Minecraft builds do not describe one.
        return ALC10.alcCreateContext(device, OpenALLoopbackCapture.getInstance().getContextAttributes());
    }

    @Inject(
            method = "init(Ljava/lang/String;Lcom/mojang/blaze3d/audio/DeviceList;Z)V",
            at = @At("TAIL"), require = 0)
    private void streamable$startRenderThread(String deviceName, DeviceList devices,
                                              boolean hrtf, CallbackInfo ci) {
        if (streamable$usingLoopback) {
            // A loopback device renders nothing on its own: this thread pulls
            // audio out of OpenAL and pushes it to playback and to capture.
            OpenALLoopbackCapture.getInstance().startRenderThread();
        }
    }

    @Inject(method = "cleanup", at = @At("HEAD"), require = 0)
    private void streamable$stopLoopback(CallbackInfo ci) {
        if (streamable$usingLoopback) {
            try {
                OpenALLoopbackCapture.getInstance().shutdown();
            } catch (Throwable t) {
                StreamAbleLog.AUDIO.debug("Error shutting down loopback capture", t);
            }
            streamable$usingLoopback = false;
        }
    }

    /**
     * Whether the user wants game audio captured.
     *
     * <p>Audio starts before the mod's runtime is built, so a missing config is
     * treated as "yes" - the common case - and the device falls back cleanly if
     * loopback turns out to be unusable.</p>
     */
    @Unique
    private static boolean streamable$captureWanted() {
        StreamAbleClient runtime = StreamAbleClient.get();
        return runtime == null || runtime.config().recording.captureGameAudio;
    }

    /** Reproduces vanilla device selection for every fallback path. */
    @Unique
    private static long streamable$openNormalDevice(String preferred, String fallback) {
        long device = 0L;
        if (preferred != null) {
            device = ALC10.alcOpenDevice(preferred);
        }
        if (device == 0L && fallback != null) {
            device = ALC10.alcOpenDevice(fallback);
        }
        if (device == 0L) {
            device = ALC10.alcOpenDevice((CharSequence) null);
        }
        if (device == 0L) {
            throw new IllegalStateException("Failed to open an OpenAL device");
        }
        return device;
    }
}
