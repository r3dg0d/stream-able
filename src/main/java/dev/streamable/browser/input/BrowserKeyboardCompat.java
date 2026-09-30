package dev.streamable.browser.input;

import dev.streamable.compat.InputCompat;
import com.mojang.blaze3d.platform.InputConstants;


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
 * delivers a real character callback for those, and synthesising a second
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
     * @param key       Minecraft key code (see InputConstants.KEY_*)
     * @param modifiers Minecraft modifier bitmask (see InputConstants.MOD_*)
     */
    public static int syntheticCodepoint(int key, int modifiers) {
        // With Ctrl/Alt/Super held the browser is receiving a shortcut, not text.
        // Emitting a character there would insert junk into the focused field
        // and can break Ctrl+A / Ctrl+C / Ctrl+V handling.
        if (hasShortcutModifier(modifiers)) {
            return NO_CHARACTER;
        }
        return switch (key) {
            case InputConstants.KEY_BACKSPACE -> 0x08;               // BS
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> 0x0D; // CR, as WM_CHAR delivers
            case InputConstants.KEY_TAB -> 0x09;                      // HT
            case InputConstants.KEY_ESCAPE -> 0x1B;                   // ESC
            // Delete deliberately produces no character on any real platform;
            // Blink resolves DeleteForward from the key-down alone.
            default -> NO_CHARACTER;
        };
    }

    public static boolean needsSyntheticCharacter(int key, int modifiers) {
        return syntheticCodepoint(key, modifiers) != NO_CHARACTER;
    }

    /** True when Ctrl, Alt or Super is held, i.e. this is a shortcut not text entry. */
    public static boolean hasShortcutModifier(int modifiers) {
        return (modifiers & (InputConstants.MOD_CONTROL | InputConstants.MOD_ALT | InputConstants.MOD_SUPER)) != 0;
    }

    /**
     * Keys whose <em>default action</em> edits the document, and which the
     * JavaScript fallback therefore watches.
     */
    public static boolean isEditingKey(int key) {
        return switch (key) {
            case InputConstants.KEY_BACKSPACE, InputConstants.KEY_DELETE,
                 InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> true;
            default -> false;
        };
    }

    /** Keys that only move the caret or focus and must never be altered. */
    public static boolean isNavigationKey(int key) {
        return switch (key) {
            case InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT, InputConstants.KEY_UP, InputConstants.KEY_DOWN,
                 InputConstants.KEY_HOME, InputConstants.KEY_END,
                 InputConstants.KEY_PAGEUP, InputConstants.KEY_PAGEDOWN -> true;
            default -> false;
        };
    }

    /** True for the modifier keys themselves, which never produce characters. */
    public static boolean isModifierKey(int key) {
        return switch (key) {
            case InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT,
                 InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL,
                 InputConstants.KEY_LALT, InputConstants.KEY_RALT,
                 InputCompat.KEY_LEFT_SUPER, InputCompat.KEY_RIGHT_SUPER -> true;
            default -> false;
        };
    }

    /** Whether this key press is the paste shortcut, which needs the clipboard bridge. */
    public static boolean isPasteShortcut(int key, int modifiers) {
        return key == InputConstants.KEY_V
                && (modifiers & (InputConstants.MOD_CONTROL | InputConstants.MOD_SUPER)) != 0;
    }
}
