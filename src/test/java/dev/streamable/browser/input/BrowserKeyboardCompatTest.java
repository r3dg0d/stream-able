package dev.streamable.browser.input;

import dev.streamable.compat.InputCompat;
import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the native half of the MCEF issue #4 workaround.
 *
 * <p>The rule being tested: send a synthetic character event exactly when a real
 * platform keyboard would, and never otherwise - synthesising one for a
 * printable key would type it twice, and synthesising one during a shortcut
 * would insert junk into the focused field.</p>
 */
class BrowserKeyboardCompatTest {

    private static final int NONE = 0;
    private static final int CTRL = InputConstants.MOD_CONTROL;
    private static final int SHIFT = InputConstants.MOD_SHIFT;

    @Test
    @DisplayName("backspace gets the 0x08 character event Blink expects")
    void backspaceProducesBackspaceCharacter() {
        assertEquals(0x08, BrowserKeyboardCompat.syntheticCodepoint(InputConstants.KEY_BACKSPACE, NONE));
        assertTrue(BrowserKeyboardCompat.needsSyntheticCharacter(InputConstants.KEY_BACKSPACE, NONE));
    }

    @Test
    @DisplayName("enter and keypad enter both produce carriage return")
    void enterProducesCarriageReturn() {
        assertEquals(0x0D, BrowserKeyboardCompat.syntheticCodepoint(InputConstants.KEY_RETURN, NONE));
        assertEquals(0x0D, BrowserKeyboardCompat.syntheticCodepoint(InputConstants.KEY_NUMPADENTER, NONE));
    }

    @Test
    void tabAndEscapeProduceTheirControlCharacters() {
        assertEquals(0x09, BrowserKeyboardCompat.syntheticCodepoint(InputConstants.KEY_TAB, NONE));
        assertEquals(0x1B, BrowserKeyboardCompat.syntheticCodepoint(InputConstants.KEY_ESCAPE, NONE));
    }

    @Test
    @DisplayName("delete gets no character event, matching real platforms")
    void deleteProducesNoCharacter() {
        // Windows does not emit WM_CHAR for Delete; Blink resolves DeleteForward
        // from the key-down alone.
        assertEquals(BrowserKeyboardCompat.NO_CHARACTER,
                BrowserKeyboardCompat.syntheticCodepoint(InputConstants.KEY_DELETE, NONE));
        assertTrue(BrowserKeyboardCompat.isEditingKey(InputConstants.KEY_DELETE));
    }

    @Test
    @DisplayName("printable keys are never synthesised - Minecraft already sends them")
    void printableKeysAreLeftAlone() {
        for (int key = InputConstants.KEY_A; key <= InputConstants.KEY_Z; key++) {
            assertEquals(BrowserKeyboardCompat.NO_CHARACTER,
                    BrowserKeyboardCompat.syntheticCodepoint(key, NONE),
                    "letter key " + key + " must not be double-typed");
        }
        for (int key = InputConstants.KEY_0; key <= InputConstants.KEY_9; key++) {
            assertEquals(BrowserKeyboardCompat.NO_CHARACTER,
                    BrowserKeyboardCompat.syntheticCodepoint(key, NONE),
                    "digit key " + key + " must not be double-typed");
        }
        assertEquals(BrowserKeyboardCompat.NO_CHARACTER,
                BrowserKeyboardCompat.syntheticCodepoint(InputConstants.KEY_SPACE, NONE));
    }

    @Test
    void arrowKeysAndNavigationProduceNoCharacter() {
        int[] navigation = {InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT, InputConstants.KEY_UP, InputConstants.KEY_DOWN,
                InputConstants.KEY_HOME, InputConstants.KEY_END, InputConstants.KEY_PAGEUP, InputConstants.KEY_PAGEDOWN};
        for (int key : navigation) {
            assertEquals(BrowserKeyboardCompat.NO_CHARACTER,
                    BrowserKeyboardCompat.syntheticCodepoint(key, NONE));
            assertTrue(BrowserKeyboardCompat.isNavigationKey(key));
        }
    }

    @Test
    void modifierKeysProduceNoCharacter() {
        int[] modifiers = {InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT,
                InputConstants.KEY_LCONTROL, InputConstants.KEY_LALT, InputCompat.KEY_LEFT_SUPER};
        for (int key : modifiers) {
            assertEquals(BrowserKeyboardCompat.NO_CHARACTER,
                    BrowserKeyboardCompat.syntheticCodepoint(key, NONE));
            assertTrue(BrowserKeyboardCompat.isModifierKey(key));
        }
    }

    @Test
    @DisplayName("Ctrl shortcuts never insert text")
    void clipboardShortcutsSuppressCharacters() {
        // Ctrl+A / Ctrl+C / Ctrl+V / Ctrl+X are shortcuts, not text entry.
        for (int key : new int[]{InputConstants.KEY_A, InputConstants.KEY_C, InputConstants.KEY_V, InputConstants.KEY_X}) {
            assertEquals(BrowserKeyboardCompat.NO_CHARACTER,
                    BrowserKeyboardCompat.syntheticCodepoint(key, CTRL));
        }
        // Even an editing key must not emit a character while Ctrl is held:
        // Ctrl+Backspace deletes a word, it does not insert 0x08.
        assertEquals(BrowserKeyboardCompat.NO_CHARACTER,
                BrowserKeyboardCompat.syntheticCodepoint(InputConstants.KEY_BACKSPACE, CTRL));
    }

    @Test
    void shiftAloneStillAllowsEditingCharacters() {
        // Shift+Enter inserts a line break; it is still text entry.
        assertEquals(0x0D, BrowserKeyboardCompat.syntheticCodepoint(InputConstants.KEY_RETURN, SHIFT));
        assertEquals(0x08, BrowserKeyboardCompat.syntheticCodepoint(InputConstants.KEY_BACKSPACE, SHIFT));
    }

    @Test
    void detectsShortcutModifiers() {
        assertTrue(BrowserKeyboardCompat.hasShortcutModifier(CTRL));
        assertTrue(BrowserKeyboardCompat.hasShortcutModifier(InputConstants.MOD_ALT));
        assertTrue(BrowserKeyboardCompat.hasShortcutModifier(InputConstants.MOD_SUPER));
        assertFalse(BrowserKeyboardCompat.hasShortcutModifier(SHIFT));
        assertFalse(BrowserKeyboardCompat.hasShortcutModifier(NONE));
    }

    @Test
    void identifiesPasteShortcutForTheClipboardBridge() {
        assertTrue(BrowserKeyboardCompat.isPasteShortcut(InputConstants.KEY_V, CTRL));
        assertTrue(BrowserKeyboardCompat.isPasteShortcut(InputConstants.KEY_V, InputConstants.MOD_SUPER));
        assertFalse(BrowserKeyboardCompat.isPasteShortcut(InputConstants.KEY_V, NONE));
        assertFalse(BrowserKeyboardCompat.isPasteShortcut(InputConstants.KEY_C, CTRL));
    }

    @Test
    void editingKeysAreExactlyTheOnesTheShimWatches() {
        assertTrue(BrowserKeyboardCompat.isEditingKey(InputConstants.KEY_BACKSPACE));
        assertTrue(BrowserKeyboardCompat.isEditingKey(InputConstants.KEY_RETURN));
        assertTrue(BrowserKeyboardCompat.isEditingKey(InputConstants.KEY_NUMPADENTER));
        assertFalse(BrowserKeyboardCompat.isEditingKey(InputConstants.KEY_A));
        assertFalse(BrowserKeyboardCompat.isEditingKey(InputConstants.KEY_LEFT));
    }
}
