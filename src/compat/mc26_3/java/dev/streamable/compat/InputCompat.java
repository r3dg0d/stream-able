package dev.streamable.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.sdl.SDLMouse;

/**
 * Input calls whose shape differs between Minecraft versions. Every supported
 * version has its own copy (src/compat/&lt;line&gt;); shared code uses
 * {@link InputConstants} for key, modifier and mouse-button values, which adapt
 * to each version's numbering, and this class for the rest.
 * This is the 26.3 implementation: Minecraft moved from GLFW to SDL, so keys are
 * SDL scancodes, mouse buttons are numbered from 1, and modifiers are SDL masks.
 */
public final class InputCompat {

    public static final int KEY_LEFT_SUPER = InputConstants.KEY_LGUI;
    public static final int KEY_RIGHT_SUPER = InputConstants.KEY_RGUI;

    private InputCompat() {
    }

    /** Whether a keyboard key is physically down. */
    public static boolean isKeyDown(Minecraft client, int key) {
        return InputConstants.isKeyDown(key);
    }

    /**
     * Whether a mouse button (an {@code InputConstants.MOUSE_BUTTON_*} value) is
     * physically down. Minecraft's numbering (left 1, middle 2, right 3, then 4, 5...)
     * is SDL's, so the button maps straight onto SDL's button mask.
     */
    public static boolean isMouseButtonDown(Minecraft client, int button) {
        if (button < 1 || button > 31) {
            return false;
        }
        int mask = SDLMouse.SDL_GetMouseState(null, null);
        return (mask & (1 << (button - 1))) != 0;
    }

    /** True for a bound keyboard key, as opposed to a mouse button. */
    public static boolean isKeyboardKey(InputConstants.Key key) {
        return key.getType() == InputConstants.Type.KEYBOARD;
    }

    /** Registers-ready key mapping bound to a keyboard key. */
    public static KeyMapping keyMapping(String translationKey, int defaultKey, KeyMapping.Category category) {
        return new KeyMapping(translationKey, defaultKey, category);
    }

    /** The event's second code: the SDL keycode on 26.3. */
    public static int auxCode(KeyEvent event) {
        return event.keycode();
    }
}
