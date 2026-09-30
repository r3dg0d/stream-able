package dev.streamable.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Client GUI state accessors. Minecraft 26.1.x keeps the current screen, the
 * screen setter and the F1 flag directly on {@link Minecraft}; 26.2 moved them
 * onto {@code Minecraft.gui}. This is the 26.1.x implementation.
 */
public final class ClientGui {

    private ClientGui() {
    }

    public static Screen screen() {
        return Minecraft.getInstance().screen;
    }

    public static void setScreen(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }

    /** Whether the player has hidden the in-game HUD (F1). */
    public static boolean hudHidden() {
        return Minecraft.getInstance().options.hideGui;
    }
}
