package dev.streamable.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import dev.streamable.mixin.GameRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/**
 * Render-side calls whose shape differs between Minecraft versions. Every
 * supported version has its own copy of this class (src/compat/&lt;line&gt;);
 * shared code calls only these methods. This is the 26.1.x implementation.
 */
public final class RenderCompat {

    private RenderCompat() {
    }

    /** The main render target the game draws into, or {@code null}. */
    public static RenderTarget mainRenderTarget() {
        return Minecraft.getInstance().getMainRenderTarget();
    }

    /** The GUI render state the frame's screens are extracted into. */
    public static GuiRenderState guiRenderState(GameRenderer gameRenderer) {
        return gameRenderer.getGameRenderState().guiRenderState;
    }

    /** Renders and ends the GUI frame currently held by the game's GUI renderer. */
    public static void renderGuiPass(GameRenderer gameRenderer) {
        GameRendererAccessor access = (GameRendererAccessor) gameRenderer;
        GuiRenderer renderer = access.streamable$guiRenderer();
        renderer.render(access.streamable$fogRenderer().getBuffer(FogRenderer.FogMode.NONE));
        renderer.endFrame();
    }

    /** Creates an RGBA8 texture usable as a render attachment, sampler input and copy source. */
    public static GpuTexture createRgba8Texture(GpuDevice device, String label, int width, int height) {
        return device.createTexture(label,
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                TextureFormat.RGBA8, width, height, 1, 1);
    }
}
