package dev.streamable.browser.input;

import org.lwjgl.glfw.GLFW;

/**
 * Works around MCEF/JCEF issue #4: editing keys do nothing in an off-screen browser.
 *
 * <h2>The problem</h2>
 * <p>MCEF forwards a key press as an AWT {@code KEY_PRESSED} event, which
 * java-cef translates into a CEF event of type {@code KEYEVENT_RAWKEYDOWN}.
 * There is no path from {@code sendKeyEvent} to {@code KEYEVENT_KEYDOWN}, and
 * Blink's editing commands for Backspace and Enter are not reached from a bare
 * raw-keydown in the OSR pipeline. The visible symptom is that a user can type
 * {@code hello} into a page but cannot delete a character or submit the field -
 * see <a href="https://github.com/DimasKama/mcef-modern/issues/4">issue #4</a>.</p>
 *
 * <h2>The fix, in two layers</h2>
 * <ol>
 *   <li><b>Correct event emulation (this class).</b> On a real platform, pressing
 *       Backspace produces <em>both</em> a key-down and a character message
 *       ({@code WM_CHAR} {@code 0x08} on Windows, the equivalent on X11). MCEF
 *       already exposes {@code onCharTyped}, which java-cef maps to
 *       {@code KEYEVENT_CHAR}. So Stream-able sends the character event that the
 *       platform would have sent, using only MCEF's public API - no patched
 *       fork, no reflection. {@link #syntheticCodepoint(int, int)} decides which
 *       keys need one.</li>
 *   <li><b>A self-verifying JavaScript fallback</b> (see
 *       {@code assets/streamable/browser/input-shim.js}). It watches for an
 *       editing key, lets the native default action run, and only performs the
 *       edit itself if the DOM verifiably did not change. It therefore cannot
 *       double-delete, it only ever touches the focused editable element, and it
 *       becomes dormant automatically if MCEF or JCEF fixes the underlying bug.</li>
 * </ol>
 *
 * <p>Ordinary printable keys are deliberately excluded: Minecraft already
 * delivers a real GLFW character callback for those, and synthesising a second
 * one would type every letter twice.</p>
 */
public final class BrowserKeyboardCompat {

    /** No character event should be sent for this key. */
    public static final int NO_CHARACTER = -1;

    private BrowserKeyboardCompat() {
    }

    /**
     * The character code a real keyboard would emit alongside this key press,
     * or {@link #NO_CHARACTER}.
     *
     * @param glfwKey   GLFW key code
     * @param modifiers GLFW modifier bitmask
     */
    public static int syntheticCodepoint(int glfwKey, int modifiers) {
        // With Ctrl/Alt/Super held the browser is receiving a shortcut, not text.
        // Emitting a character there would insert junk into the focused field
        // and can break Ctrl+A / Ctrl+C / Ctrl+V handling.
        if (hasShortcutModifier(modifiers)) {
            return NO_CHARACTER;
        }
        return switch (glfwKey) {
            case GLFW.GLFW_KEY_BACKSPACE -> 0x08;               // BS
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> 0x0D; // CR, as WM_CHAR delivers
            case GLFW.GLFW_KEY_TAB -> 0x09;                      // HT
            case GLFW.GLFW_KEY_ESCAPE -> 0x1B;                   // ESC
            // Delete deliberately produces no character on any real platform;
            // Blink resolves DeleteForward from the key-down alone.
            default -> NO_CHARACTER;
        };
    }

    public static boolean needsSyntheticCharacter(int glfwKey, int modifiers) {
        return syntheticCodepoint(glfwKey, modifiers) != NO_CHARACTER;
    }

    /** True when Ctrl, Alt or Super is held, i.e. this is a shortcut not text entry. */
    public static boolean hasShortcutModifier(int modifiers) {
        return (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) != 0;
    }

    /**
     * Keys whose <em>default action</em> edits the document, and which the
     * JavaScript fallback therefore watches.
     */
    public static boolean isEditingKey(int glfwKey) {
        return switch (glfwKey) {
            case GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE,
                 GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> true;
            default -> false;
        };
    }

    /** Keys that only move the caret or focus and must never be altered. */
    public static boolean isNavigationKey(int glfwKey) {
        return switch (glfwKey) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN,
                 GLFW.GLFW_KEY_HOME, GLFW.GLFW_KEY_END,
                 GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN -> true;
            default -> false;
        };
    }

    /** True for the modifier keys themselves, which never produce characters. */
    public static boolean isModifierKey(int glfwKey) {
        return switch (glfwKey) {
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT,
                 GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL,
                 GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT,
                 GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> true;
            default -> false;
        };
    }

    /** Whether this key press is the paste shortcut, which needs the clipboard bridge. */
    public static boolean isPasteShortcut(int glfwKey, int modifiers) {
        return glfwKey == GLFW.GLFW_KEY_V
                && (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
    }
}
