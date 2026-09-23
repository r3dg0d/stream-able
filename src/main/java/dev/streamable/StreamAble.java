package dev.streamable;

import dev.streamable.ui.SourceEditorScreen;
import dev.streamable.ui.StreamHealthHud;
import dev.streamable.ui.StudioScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
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

    private StreamAbleClient runtime;

    @Override
    public void onInitializeClient() {
        runtime = StreamAbleClient.create();
        runtime.initialise();

        registerKeyBindings();
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "stream_health"), new StreamHealthHud());

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
        runtime.onClientTick();
    }
}
