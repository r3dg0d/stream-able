package dev.streamable.mixin;

import dev.streamable.StreamAbleClient;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notices advancement toasts, the client's own signal that the player just
 * earned an advancement, for automatic replay clips. Observation only: the
 * toast is shown exactly as it would be without Stream-able.
 */
@Mixin(ToastManager.class)
public abstract class ToastManagerMixin {

    @Inject(method = "addToast", at = @At("HEAD"))
    private void streamable$onToast(Toast toast, CallbackInfo ci) {
        if (toast instanceof AdvancementToast) {
            StreamAbleClient runtime = StreamAbleClient.get();
            if (runtime != null) {
                runtime.noteAdvancementEarned();
            }
        }
    }
}
