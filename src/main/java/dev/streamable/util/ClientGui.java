package dev.streamable.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Thin accessors for client GUI state that moved onto {@link net.minecraft.client.gui.Gui}
 * in Minecraft 26.2 ({@code Minecraft.screen}/{@code setScreen}/{@code options.hideGui}
 * no longer exist on {@link Minecraft}).
 *
 * <p>Call sites go through this helper so a future relocation is a one-file change.</p>
 */
public final class ClientGui {

    private ClientGui() {
    }

    public static Screen screen() {
        return Minecraft.getInstance().gui.screen();
    }

    public static void setScreen(Screen screen) {
        Minecraft.getInstance().gui.setScreen(screen);
    }

    /** Whether the player has hidden the in-game HUD (F1). */
    public static boolean hudHidden() {
        return Minecraft.getInstance().gui.hud.isHidden();
    }
}
