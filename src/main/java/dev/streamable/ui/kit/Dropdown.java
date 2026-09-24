package dev.streamable.ui.kit;

import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** A combo box: shows the selection, opens a popup list above everything else. */
public final class Dropdown extends UiNode {

    private final String label;
    private final Supplier<List<String>> options;
    private final IntSupplier selected;
    private final IntConsumer setter;
    private Supplier<String> placeholder = () -> "Select...";

    public Dropdown(String label, Supplier<List<String>> options, IntSupplier selected, IntConsumer setter) {
        this.label = label;
        this.options = options;
        this.selected = selected;
        this.setter = setter;
    }

    public Dropdown placeholder(Supplier<String> text) {
        this.placeholder = text;
        return this;
    }

    @Override
    public int preferredHeight(int availableWidth) {
        return label == null || label.isEmpty() ? Theme.CONTROL_HEIGHT : Theme.CONTROL_HEIGHT + 11;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    private int boxY() {
        return label == null || label.isEmpty() ? y : y + 11;
    }

    @Override
    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= boxY() && my < boxY() + Theme.CONTROL_HEIGHT;
    }

    @Override
    protected void renderSelf(Painter p) {
        if (label != null && !label.isEmpty()) {
            p.text(label, x, y, Theme.TEXT_MUTED, Theme.TEXT_CAPTION, Painter.Weight.SEMIBOLD);
        }
        int by = boxY();
        int bg = Theme.mix(Theme.FIELD, Theme.SURFACE_HOVER, hover.eased() * 0.6f);
        p.roundRect(x, by, width, Theme.CONTROL_HEIGHT, Theme.RADIUS, bg);
        p.roundBorder(x, by, width, Theme.CONTROL_HEIGHT, Theme.RADIUS, 1f, Theme.mix(Theme.BORDER, Theme.BORDER_STRONG, hover.eased()));
        List<String> values = options.get();
        int index = selected.getAsInt();
        String shown = index >= 0 && index < values.size() ? values.get(index) : placeholder.get();
        p.textClipped(shown, x + 6, by + 4, width - 20, isEnabled() ? Theme.TEXT : Theme.TEXT_DISABLED, 1f, Painter.Weight.REGULAR);
        Icons.draw(p, Icons.Icon.CHEVRON_DOWN, x + width - 13, by + 4, 8, Theme.TEXT_MUTED);
    }

    @Override
    public boolean mouseDown(double mx, double my, int button) {
        if (button != 0 || !isEnabled()) {
            return false;
        }
        open();
        return true;
    }

    @Override
    public boolean activate() {
        if (isEnabled()) {
            open();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyDown(int key, int modifiers) {
        List<String> values = options.get();
        if (values.isEmpty()) {
            return false;
        }
        int index = selected.getAsInt();
        if (key == GLFW.GLFW_KEY_UP) {
            setter.accept(Math.max(0, index - 1));
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN) {
            setter.accept(Math.min(values.size() - 1, index + 1));
            return true;
        }
        return false;
    }

    private void open() {
        UiScreen screen = screen();
        if (screen != null) {
            screen.openPopup(new ListPopup(this, options.get(), selected.getAsInt(), setter));
        }
    }

    /** The open list. Keyboard: up/down, Enter selects, Escape closes. */
    static final class ListPopup extends UiNode {
        private final Dropdown owner;
        private final List<String> values;
        private final IntConsumer setter;
        private int highlight;
        private int scroll;
        private static final int ROW = 14;

        ListPopup(Dropdown owner, List<String> values, int selected, IntConsumer setter) {
            this.owner = owner;
            this.values = values;
            this.setter = setter;
            this.highlight = Math.max(0, selected);
        }

        void place(int screenWidth, int screenHeight) {
            int rows = Math.min(values.size(), 12);
            int h = rows * ROW + 6;
            int w = Math.max(owner.width, 120);
            int px = Math.min(owner.x, screenWidth - w - 4);
            int py = owner.boxY() + Theme.CONTROL_HEIGHT + 2;
            if (py + h > screenHeight - 4) {
                py = Math.max(4, owner.boxY() - h - 2);
            }
            setBounds(px, py, w, h);
            int visible = rows;
            if (highlight < scroll) {
                scroll = highlight;
            } else if (highlight >= scroll + visible) {
                scroll = highlight - visible + 1;
            }
        }

        @Override
        protected void renderSelf(Painter p) {
            p.shadow(x, y, width, height, Theme.RADIUS, 6, Theme.SHADOW);
            p.roundRect(x, y, width, height, Theme.RADIUS, Theme.SURFACE_RAISED);
            p.roundBorder(x, y, width, height, Theme.RADIUS, 1f, Theme.BORDER_STRONG);
            int rows = Math.min(values.size(), 12);
            for (int i = 0; i < rows; i++) {
                int index = scroll + i;
                if (index >= values.size()) {
                    break;
                }
                int ry = y + 3 + i * ROW;
                boolean hot = p.hovered(x, ry, width, ROW);
                if (hot) {
                    highlight = index;
                }
                if (index == highlight) {
                    p.roundRect(x + 3, ry, width - 6, ROW, Theme.RADIUS_SMALL, Theme.SURFACE_HOVER);
                }
                p.textClipped(values.get(index), x + 8, ry + 3, width - 16, Theme.TEXT, 1f, Painter.Weight.REGULAR);
            }
            if (values.size() > rows) {
                float frac = rows / (float) values.size();
                float barH = (height - 6) * frac;
                float barY = y + 3 + (height - 6 - barH) * (scroll / (float) Math.max(1, values.size() - rows));
                p.roundRect(x + width - 4, barY, 2, barH, 1, Theme.BORDER_STRONG);
            }
        }

        @Override
        public boolean mouseDown(double mx, double my, int button) {
            int index = scroll + (int) ((my - y - 3) / ROW);
            if (index >= 0 && index < values.size()) {
                setter.accept(index);
            }
            UiScreen screen = owner.screen();
            if (screen != null) {
                screen.closePopup();
                screen.focusNode(owner);
            }
            return true;
        }

        @Override
        public boolean mouseScroll(double mx, double my, double amount) {
            scroll = Math.clamp(scroll - (int) Math.signum(amount), 0, Math.max(0, values.size() - 12));
            return true;
        }

        @Override
        public boolean keyDown(int key, int modifiers) {
            switch (key) {
                case GLFW.GLFW_KEY_UP -> highlight = Math.max(0, highlight - 1);
                case GLFW.GLFW_KEY_DOWN -> highlight = Math.min(values.size() - 1, highlight + 1);
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                    setter.accept(highlight);
                    UiScreen screen = owner.screen();
                    if (screen != null) {
                        screen.closePopup();
                        screen.focusNode(owner);
                    }
                }
                default -> {
                    return false;
                }
            }
            return true;
        }
    }
}
