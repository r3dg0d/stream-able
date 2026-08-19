package dev.streamable.mixin;

import dev.streamable.StreamAbleClient;
import dev.streamable.StreamAbleLog;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single render hook Stream-able needs.
 *
 * <p><b>Risk note.</b> This is the mod's one high-risk mixin. It injects at the
 * tail of the game's render pass - after the world, entities, particles, the HUD
 * and any open screen have all been drawn - which is the only point where a
 * complete frame exists to composite and capture. It is a {@code TAIL} inject
 * with no local capture and no cancellation, so it cannot alter what Minecraft
 * renders, and the callback swallows its own exceptions: a compositor failure
 * degrades browser sources and capture rather than crashing the render loop.</p>
 *
 * <p>A narrower integration point was preferred but none exists: Fabric's HUD
 * API renders inside the GUI layer, which is too early to capture an assembled
 * frame and cannot reach an off-screen framebuffer.</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"))
    private void streamable$onFrameRendered(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        StreamAbleClient runtime = StreamAbleClient.get();
        if (runtime == null) {
            return;
        }
        try {
            runtime.onFrameRendered();
        } catch (Throwable t) {
            StreamAbleLog.COMPOSITOR.warn("Stream-able frame hook failed; skipping this frame.", t);
        }
    }
}
