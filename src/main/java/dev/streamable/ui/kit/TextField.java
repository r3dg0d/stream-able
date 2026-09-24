package dev.streamable.ui.kit;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Single-line text input with cursor, selection, clipboard and an optional
 * secret mode (stream keys: masked on screen, revealed only on request, and
 * the real value never drawn while masked).
 */
public final class TextField extends UiNode {

    private final String label;
    private final Supplier<String> source;
    private final Consumer<String> sink;
    private String text;
    private String placeholder = "";
    private int cursor;
    private int anchor;
    private int scroll;
    private boolean secret;
    private boolean revealed;
    private Predicate<String> accepts = s -> true;
    private int maxLength = 512;
    private long focusedAt;

    public TextField(String label, Supplier<String> source, Consumer<String> sink) {
        this.label = label;
        this.source = source;
        this.sink = sink;
        this.text = source.get() == null ? "" : source.get();
    }

    public TextField placeholder(String value) {
        this.placeholder = value;
        return this;
    }

    public TextField secret() {
        this.secret = true;
        return this;
    }

    public TextField numeric() {
        this.accepts = s -> s.matches("-?[0-9]*\\.?[0-9]*");
        return this;
    }

    public TextField maxLength(int value) {
        this.maxLength = value;
        return this;
    }

    public boolean isRevealed() {
        return revealed;
    }

    public void toggleRevealed() {
        revealed = !revealed;
    }

    /** Re-reads the model when it changed elsewhere and this field is not being edited. */
    public void refresh() {
        if (!isFocused()) {
            String current = source.get();
            if (current != null && !current.equals(text)) {
                text = current;
                cursor = Math.min(cursor, text.length());
                anchor = cursor;
            }
        }
    }

    @Override
    public int preferredHeight(int availableWidth) {
        return label == null || label.isEmpty() ? Theme.CONTROL_HEIGHT : Theme.CONTROL_HEIGHT + 11;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    protected boolean showFocusRing() {
        return false;
    }

    private int boxY() {
        return label == null || label.isEmpty() ? y : y + 11;
    }

    private String display() {
        return secret && !revealed ? "•".repeat(text.length()) : text;
    }

    @Override
    protected void renderSelf(Painter p) {
        refresh();
        if (label != null && !label.isEmpty()) {
            p.text(label, x, y, Theme.TEXT_MUTED, Theme.TEXT_CAPTION, Painter.Weight.SEMIBOLD);
        }
        int by = boxY();
        boolean focused = isFocused();
        p.roundRect(x, by, width, Theme.CONTROL_HEIGHT, Theme.RADIUS, Theme.FIELD);
        p.roundBorder(x, by, width, Theme.CONTROL_HEIGHT, Theme.RADIUS, 1f,
                focused ? Theme.FOCUS_RING : Theme.mix(Theme.BORDER, Theme.BORDER_STRONG, hover.eased()));
        String shown = display();
        int inner = width - 12;
        // Keep the cursor visible.
        int cursorX = p.textWidth(shown.substring(0, Math.min(cursor, shown.length())));
        if (cursorX - scroll > inner) {
            scroll = cursorX - inner;
        } else if (cursorX < scroll) {
            scroll = cursorX;
        }
        p.pushClip(x + 5, by, width - 10, Theme.CONTROL_HEIGHT);
        float tx = x + 6 - scroll;
        float ty = by + 4;
        if (shown.isEmpty() && !focused) {
            p.text(placeholder, x + 6, ty, Theme.TEXT_MUTED);
        }
        if (focused && anchor != cursor) {
            int a = Math.min(anchor, cursor);
            int b = Math.max(anchor, cursor);
            float sx = tx + p.textWidth(shown.substring(0, a));
            float ex = tx + p.textWidth(shown.substring(0, b));
            p.roundRect(sx, by + 3, ex - sx, Theme.CONTROL_HEIGHT - 6, 1, Theme.ACCENT_SOFT);
        }
        p.text(shown, tx, ty, isEnabled() ? Theme.TEXT : Theme.TEXT_DISABLED);
        if (focused && ((System.currentTimeMillis() - focusedAt) / 530) % 2 == 0) {
            p.fill(Math.round(tx + cursorX), by + 3, 1, Theme.CONTROL_HEIGHT - 6, Theme.TEXT);
        }
        p.popClip();
    }

    @Override
    public void onFocusChanged(boolean focused) {
        focusedAt = System.currentTimeMillis();
        if (!focused) {
            anchor = cursor;
        }
    }

    @Override
    public boolean mouseDown(double mx, double my, int button) {
        if (button != 0 || !isEnabled()) {
            return false;
        }
        requestFocus();
        UiScreen screen = screen();
        String shown = display();
        int target = shown.length();
        if (screen != null) {
            for (int i = 0; i <= shown.length(); i++) {
                if (x + 6 - scroll + screen.textWidth(shown.substring(0, i)) >= mx) {
                    target = Math.max(0, i - 1 < 0 ? 0 : i);
                    break;
                }
            }
        }
        cursor = Math.min(target, text.length());
        anchor = cursor;
        return true;
    }

    private void commit(String next) {
        if (next.length() > maxLength || !accepts.test(next)) {
            return;
        }
        text = next;
        sink.accept(text);
    }

    private void insert(String value) {
        int a = Math.min(anchor, cursor);
        int b = Math.max(anchor, cursor);
        String next = text.substring(0, a) + value + text.substring(b);
        if (next.length() > maxLength || !accepts.test(next)) {
            return;
        }
        commit(next);
        cursor = a + value.length();
        anchor = cursor;
    }

    @Override
    public boolean charTyped(int codepoint) {
        if (!isEnabled() || codepoint < 32) {
            return false;
        }
        insert(new String(Character.toChars(codepoint)));
        return true;
    }

    @Override
    public boolean keyDown(int key, int modifiers) {
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean control = (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        var keyboard = Minecraft.getInstance().keyboardHandler;
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (anchor != cursor) {
                    insert("");
                } else if (cursor > 0) {
                    commit(text.substring(0, cursor - 1) + text.substring(cursor));
                    cursor--;
                    anchor = cursor;
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (anchor != cursor) {
                    insert("");
                } else if (cursor < text.length()) {
                    commit(text.substring(0, cursor) + text.substring(cursor + 1));
                }
            }
            case GLFW.GLFW_KEY_LEFT -> {
                cursor = Math.max(0, cursor - 1);
                if (!shift) {
                    anchor = cursor;
                }
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                cursor = Math.min(text.length(), cursor + 1);
                if (!shift) {
                    anchor = cursor;
                }
            }
            case GLFW.GLFW_KEY_HOME -> {
                cursor = 0;
                if (!shift) {
                    anchor = 0;
                }
            }
            case GLFW.GLFW_KEY_END -> {
                cursor = text.length();
                if (!shift) {
                    anchor = cursor;
                }
            }
            case GLFW.GLFW_KEY_A -> {
                if (!control) {
                    return false;
                }
                anchor = 0;
                cursor = text.length();
            }
            case GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_X -> {
                if (!control) {
                    return false;
                }
                // Masked secrets are never copied out of the field.
                if (anchor != cursor && !(secret && !revealed)) {
                    keyboard.setClipboard(text.substring(Math.min(anchor, cursor), Math.max(anchor, cursor)));
                    if (key == GLFW.GLFW_KEY_X) {
                        insert("");
                    }
                }
            }
            case GLFW.GLFW_KEY_V -> {
                if (!control) {
                    return false;
                }
                String clip = keyboard.getClipboard().replace("\n", "").replace("\r", "");
                insert(clip.strip());
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                UiScreen screen = screen();
                if (screen != null) {
                    screen.focusNode(null);
                }
            }
            default -> {
                return false;
            }
        }
        focusedAt = System.currentTimeMillis();
        return true;
    }
}
