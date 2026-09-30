package dev.streamable.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.streamable.StreamAble;
import dev.streamable.StreamAbleClient;
import dev.streamable.compositor.GraphicsBackend;
import dev.streamable.ui.SourceEditorScreen;
import dev.streamable.ui.studio.StudioScreen;
import dev.streamable.util.ClientGui;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;

/**
 * Boots the mod inside a real client on whichever Minecraft version is being built.
 *
 * <p>Everything here fails only at runtime, never at compile time: the render mixin's
 * target, the mod's shaders and pipeline, key numbering and mouse handling. Each check
 * therefore exercises one of the things that broke when porting to 26.3.</p>
 */
public final class StreamAbleBootGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.waitTicks(60);

            // 1. The client booted with every mixin applied (a failed mixin aborts startup, since
            //    defaultRequire is 1) and the render hook is actually firing.
            long frames = context.computeOnClient(mc -> StreamAbleClient.get().framesSeen());
            check(frames > 0, "the GameRenderer mixin never ran (frames seen: " + frames + ")");

            // 2. The backend guard agrees with what the client is really using.
            boolean openGl = context.computeOnClient(mc -> GraphicsBackend.openGlAvailable());
            String problem = context.computeOnClient(mc -> GraphicsBackend.unsupportedReason());
            if (openGl) {
                check(problem == null, "OpenGL is available but Stream-able reports: " + problem);
            } else {
                check(problem != null, "not on OpenGL but no reason was reported to the player");
            }
            System.out.println("[streamable-gametest] backend: " + (openGl ? "OpenGL" : "not OpenGL (Vulkan)")
                    + ", frames seen: " + frames);

            // 3. Key numbering: the Studio key binding, pressed through the game's own input path.
            KeyMapping openStudio = mapping("key.streamable.open_studio");
            context.getInput().pressKey(openStudio);
            context.waitForScreen(StudioScreen.class);
            context.waitTicks(20); // let the rounded-rect pipeline and shaders draw a few frames
            context.takeScreenshot("streamable-studio");

            // 4. The Studio closes with Escape (SDL scancode on 26.3, GLFW code before).
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitFor(mc -> !(ClientGui.screen() instanceof StudioScreen));

            // 5. A second screen with its own pipeline use: the canvas editor.
            KeyMapping editor = mapping("key.streamable.toggle_source_editor");
            context.getInput().pressKey(editor);
            context.waitForScreen(SourceEditorScreen.class);
            context.waitTicks(10);
            context.takeScreenshot("streamable-source-editor");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE); // the editor's documented way out
            context.waitFor(mc -> !(ClientGui.screen() instanceof SourceEditorScreen));

            // Still alive and still rendering after all of that.
            long after = context.computeOnClient(mc -> StreamAbleClient.get().framesSeen());
            check(after > frames, "rendering stopped after opening the screens");
        }
    }

    private static KeyMapping mapping(String name) {
        for (KeyMapping m : StreamAble.keyMappings()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("no key mapping named " + name);
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
