package dev.streamable.mixin;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to the GUI renderer, so the stream HUD can be drawn in a second
 * GUI pass after the frame has been captured (see {@link dev.streamable.ui.StreamHud}).
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    @Accessor("guiRenderer")
    GuiRenderer streamable$guiRenderer();

    @Accessor("fogRenderer")
    FogRenderer streamable$fogRenderer();

    @Accessor("useUiLightmap")
    void streamable$setUseUiLightmap(boolean value);
}
