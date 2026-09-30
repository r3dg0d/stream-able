package dev.streamable.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Input calls whose shape differs between Minecraft versions. Every supported
 * version has its own copy (src/compat/&lt;line&gt;); shared code uses
 * {@link InputConstants} for key, modifier and mouse-button values, which adapt
 * to each version's numbering, and this class for the rest.
 * This is the 26.1.x / 26.2 implementation (GLFW key codes).
 */
public final class InputCompat {

    public static final int KEY_LEFT_SUPER = InputConstants.KEY_LSUPER;
    public static final int KEY_RIGHT_SUPER = InputConstants.KEY_RSUPER;

    private InputCompat() {
    }

    /** Whether a keyboard key is physically down. */
    public static boolean isKeyDown(Minecraft client, int key) {
        return InputConstants.isKeyDown(client.getWindow(), key);
    }

    /** Whether a mouse button (an {@code InputConstants.MOUSE_BUTTON_*} value) is physically down. */
    public static boolean isMouseButtonDown(Minecraft client, int button) {
        return GLFW.glfwGetMouseButton(client.getWindow().handle(), button) == GLFW.GLFW_PRESS;
    }

    /** True for a bound keyboard key, as opposed to a mouse button. */
    public static boolean isKeyboardKey(InputConstants.Key key) {
        return key.getType() == InputConstants.Type.KEYSYM;
    }

    /** Registers-ready key mapping bound to a keyboard key. */
    public static KeyMapping keyMapping(String translationKey, int defaultKey, KeyMapping.Category category) {
        return new KeyMapping(translationKey, InputConstants.Type.KEYSYM, defaultKey, category);
    }

    /** The event's second code: the scancode on 26.1.x / 26.2. */
    public static int auxCode(KeyEvent event) {
        return event.scancode();
    }
}
