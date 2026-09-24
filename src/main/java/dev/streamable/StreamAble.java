package dev.streamable;

import dev.streamable.ui.SourceEditorScreen;
import dev.streamable.ui.studio.StudioScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * Client entry point.
 *
 * <p>Keeps startup deliberately thin: configuration is read, the runtime is
 * created and keybinds are registered, but nothing heavy happens here. The
 * browser engine initialises asynchronously and FFmpeg is only probed when it
 * is first needed, so neither can delay the main menu.</p>
 */
public final class StreamAble implements ClientModInitializer {

    public static final String MOD_ID = "streamable";

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

    private static KeyMapping toggleRecordingKey;
    private static KeyMapping toggleStreamingKey;
    private static KeyMapping openStudioKey;
    private static KeyMapping toggleSourceEditorKey;
    private static KeyMapping toggleHudKey;
    private static KeyMapping toggleMicMuteKey;
    private static KeyMapping pushToTalkKey;
    private static KeyMapping pushToMuteKey;
    private static KeyMapping toggleNoiseBypassKey;

    private StreamAbleClient runtime;

    @Override
    public void onInitializeClient() {
        runtime = StreamAbleClient.create();
        runtime.initialise();

        registerKeyBindings();

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            runtime.shutdown();
            runtime.releaseGpuResources();
        });

        // A hard JVM exit must not leave a half-written recording or an orphaned
        // FFmpeg/Chromium process behind.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                runtime.shutdown();
            } catch (Throwable t) {
                StreamAbleLog.CORE.warn("Error during shutdown hook", t);
            }
        }, "Stream-able Shutdown Hook"));

        StreamAbleLog.CORE.info("Stream-able ready.");
    }

    private void registerKeyBindings() {
        // Defaults avoid Minecraft's own bindings and the F3 debug chords.
        toggleRecordingKey = register("toggle_recording", GLFW.GLFW_KEY_F9);
        toggleStreamingKey = register("toggle_streaming", GLFW.GLFW_KEY_F10);
        openStudioKey = register("open_studio", GLFW.GLFW_KEY_F6);
        toggleSourceEditorKey = register("toggle_source_editor", GLFW.GLFW_KEY_F7);
        toggleHudKey = register("toggle_overlays", GLFW.GLFW_KEY_F8);
        // Microphone controls start unbound: every obvious key is already a
        // game control for someone, so the player chooses.
        toggleMicMuteKey = register("toggle_mic_mute", GLFW.GLFW_KEY_UNKNOWN);
        pushToTalkKey = register("push_to_talk", GLFW.GLFW_KEY_UNKNOWN);
        pushToMuteKey = register("push_to_mute", GLFW.GLFW_KEY_UNKNOWN);
        toggleNoiseBypassKey = register("toggle_noise_bypass", GLFW.GLFW_KEY_UNKNOWN);
    }

    /**
     * Whether a hold-type key is physically down. Polled from the key state
     * rather than from KeyMapping click events, so holding push-to-talk works
     * reliably even while a screen is open, and a release is never missed.
     */
    private static boolean held(Minecraft client, KeyMapping mapping) {
        InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mapping);
        if (key == null || key.equals(InputConstants.UNKNOWN) || client.getWindow() == null) {
            return false;
        }
        if (key.getType() == InputConstants.Type.KEYSYM) {
            return InputConstants.isKeyDown(client.getWindow(), key.getValue());
        }
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(client.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
        }
        return mapping.isDown();
    }

    /** Every Stream-able key mapping, in display order, for the Studio's shortcut list. */
    public static java.util.List<KeyMapping> keyMappings() {
        return java.util.stream.Stream.of(openStudioKey, toggleSourceEditorKey, toggleHudKey, toggleRecordingKey,
                toggleStreamingKey, toggleMicMuteKey, pushToTalkKey, pushToMuteKey, toggleNoiseBypassKey)
                .filter(java.util.Objects::nonNull).toList();
    }

    private static KeyMapping register(String name, int defaultKey) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.streamable." + name, InputConstants.Type.KEYSYM, defaultKey, CATEGORY));
    }

    private void onClientTick(Minecraft client) {
        while (toggleRecordingKey.consumeClick()) {
            if (runtime.recording().isActive()) {
                runtime.stopRecording();
            } else {
                String error = runtime.startRecording();
                if (error != null) {
                    StreamAbleLog.RECORDING.warn("Could not start recording: {}", error);
                }
            }
        }
        while (toggleStreamingKey.consumeClick()) {
            if (runtime.streaming().isLive()) {
                runtime.stopStreaming();
            } else {
                String error = runtime.startStreaming();
                if (error != null) {
                    StreamAbleLog.STREAMING.warn("Could not start streaming: {}", error);
                }
            }
        }
        while (openStudioKey.consumeClick()) {
            client.setScreen(new StudioScreen(runtime, client.screen));
        }
        while (toggleSourceEditorKey.consumeClick()) {
            if (client.screen instanceof SourceEditorScreen) {
                client.setScreen(null);
            } else {
                client.setScreen(new SourceEditorScreen(runtime));
            }
        }
        while (toggleHudKey.consumeClick()) {
            runtime.config().ui.showStreamHud = !runtime.config().ui.showStreamHud;
            runtime.markDirty();
        }
        var mic = runtime.config().microphone;
        while (toggleMicMuteKey.consumeClick()) {
            mic.muted = !mic.muted;
            mic.touch();
            runtime.markDirty();
        }
        while (toggleNoiseBypassKey.consumeClick()) {
            mic.noise.bypass = !mic.noise.bypass;
            mic.touch();
        }
        runtime.microphone().processor().setPushToTalkHeld(held(client, pushToTalkKey));
        runtime.microphone().processor().setPushToMuteHeld(mic.pushToMute && held(client, pushToMuteKey));
        runtime.onClientTick();
    }
}
