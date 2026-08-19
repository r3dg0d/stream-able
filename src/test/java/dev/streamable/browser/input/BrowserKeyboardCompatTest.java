package dev.streamable.browser.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

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
    private static final int CTRL = GLFW.GLFW_MOD_CONTROL;
    private static final int SHIFT = GLFW.GLFW_MOD_SHIFT;

    @Test
    @DisplayName("backspace gets the 0x08 character event Blink expects")
    void backspaceProducesBackspaceCharacter() {
        assertEquals(0x08, BrowserKeyboardCompat.syntheticCodepoint(GLFW.GLFW_KEY_BACKSPACE, NONE));
        assertTrue(BrowserKeyboardCompat.needsSyntheticCharacter(GLFW.GLFW_KEY_BACKSPACE, NONE));
    }

    @Test
    @DisplayName("enter and keypad enter both produce carriage return")
    void enterProducesCarriageReturn() {
        assertEquals(0x0D, BrowserKeyboardCompat.syntheticCodepoint(GLFW.GLFW_KEY_ENTER, NONE));
        assertEquals(0x0D, BrowserKeyboardCompat.syntheticCodepoint(GLFW.GLFW_KEY_KP_ENTER, NONE));
    }

    @Test
    void tabAndEscapeProduceTheirControlCharacters() {
        assertEquals(0x09, BrowserKeyboardCompat.syntheticCodepoint(GLFW.GLFW_KEY_TAB, NONE));
        assertEquals(0x1B, BrowserKeyboardCompat.syntheticCodepoint(GLFW.GLFW_KEY_ESCAPE, NONE));
    }

    @Test
    @DisplayName("delete gets no character event, matching real platforms")
    void deleteProducesNoCharacter() {
        // Windows does not emit WM_CHAR for Delete; Blink resolves DeleteForward
        // from the key-down alone.
        assertEquals(BrowserKeyboardCompat.NO_CHARACTER,
                BrowserKeyboardCompat.syntheticCodepoint(GLFW.GLFW_KEY_DELETE, NONE));
        assertTrue(BrowserKeyboardCompat.isEditingKey(GLFW.GLFW_KEY_DELETE));
    }

    @Test
    @DisplayName("printable keys are never synthesised - Minecraft already sends them")
    void printableKeysAreLeftAlone() {
        for (int key = GLFW.GLFW_KEY_A; key <= GLFW.GLFW_KEY_Z; key++) {
            assertEquals(BrowserKeyboardCompat.NO_CHARACTER,
                    BrowserKeyboardCompat.syntheticCodepoint(key, NONE),
                    "letter key " + key + " must not be double-typed");
        }
        for (int key = GLFW.GLFW_KEY_0; key <= GLFW.GLFW_KEY_9; key++) {
            assertEquals(BrowserKeyboardCompat.NO_CHARACTER,
                    BrowserKeyboardCompat.syntheticCodepoint(key, NONE),
                    "digit key " + key + " must not be double-typed");
        }
        assertEquals(BrowserKeyboardCompat.NO_CHARACTER,
                BrowserKeyboardCompat.syntheticCodepoint(GLFW.GLFW_KEY_SPACE, NONE));
    }

    @Test
    void arrowKeysAndNavigationProduceNoCharacter() {
        int[] navigation = {GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN,
                GLFW.GLFW_KEY_HOME, GLFW.GLFW_KEY_END, GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN};
        for (int key : navigation) {
            assertEquals(BrowserKeyboardCompat.NO_CHARACTER,
                    BrowserKeyboardCompat.syntheticCodepoint(key, NONE));
            assertTrue(BrowserKeyboardCompat.isNavigationKey(key));
        }
    }

    @Test
    void modifierKeysProduceNoCharacter() {
        int[] modifiers = {GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT,
                GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_LEFT_SUPER};
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
        for (int key : new int[]{GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_X}) {
            assertEquals(BrowserKeyboardCompat.NO_CHARACTER,
                    BrowserKeyboardCompat.syntheticCodepoint(key, CTRL));
        }
        // Even an editing key must not emit a character while Ctrl is held:
        // Ctrl+Backspace deletes a word, it does not insert 0x08.
        assertEquals(BrowserKeyboardCompat.NO_CHARACTER,
                BrowserKeyboardCompat.syntheticCodepoint(GLFW.GLFW_KEY_BACKSPACE, CTRL));
    }

    @Test
    void shiftAloneStillAllowsEditingCharacters() {
        // Shift+Enter inserts a line break; it is still text entry.
        assertEquals(0x0D, BrowserKeyboardCompat.syntheticCodepoint(GLFW.GLFW_KEY_ENTER, SHIFT));
        assertEquals(0x08, BrowserKeyboardCompat.syntheticCodepoint(GLFW.GLFW_KEY_BACKSPACE, SHIFT));
    }

    @Test
    void detectsShortcutModifiers() {
        assertTrue(BrowserKeyboardCompat.hasShortcutModifier(CTRL));
        assertTrue(BrowserKeyboardCompat.hasShortcutModifier(GLFW.GLFW_MOD_ALT));
        assertTrue(BrowserKeyboardCompat.hasShortcutModifier(GLFW.GLFW_MOD_SUPER));
        assertFalse(BrowserKeyboardCompat.hasShortcutModifier(SHIFT));
        assertFalse(BrowserKeyboardCompat.hasShortcutModifier(NONE));
    }

    @Test
    void identifiesPasteShortcutForTheClipboardBridge() {
        assertTrue(BrowserKeyboardCompat.isPasteShortcut(GLFW.GLFW_KEY_V, CTRL));
        assertTrue(BrowserKeyboardCompat.isPasteShortcut(GLFW.GLFW_KEY_V, GLFW.GLFW_MOD_SUPER));
        assertFalse(BrowserKeyboardCompat.isPasteShortcut(GLFW.GLFW_KEY_V, NONE));
        assertFalse(BrowserKeyboardCompat.isPasteShortcut(GLFW.GLFW_KEY_C, CTRL));
    }

    @Test
    void editingKeysAreExactlyTheOnesTheShimWatches() {
        assertTrue(BrowserKeyboardCompat.isEditingKey(GLFW.GLFW_KEY_BACKSPACE));
        assertTrue(BrowserKeyboardCompat.isEditingKey(GLFW.GLFW_KEY_ENTER));
        assertTrue(BrowserKeyboardCompat.isEditingKey(GLFW.GLFW_KEY_KP_ENTER));
        assertFalse(BrowserKeyboardCompat.isEditingKey(GLFW.GLFW_KEY_A));
        assertFalse(BrowserKeyboardCompat.isEditingKey(GLFW.GLFW_KEY_LEFT));
    }
}
