package dev.streamable.ui.kit;

import java.util.function.Supplier;

/** A compact button. Primary actions are filled; secondary are quiet surfaces. */
public final class Button extends UiNode {

    public enum Variant { PRIMARY, SECONDARY, GHOST, DANGER, LIVE }

    private final Supplier<String> label;
    private Supplier<Icons.Icon> icon = () -> null;
    private Supplier<Variant> variant = () -> Variant.SECONDARY;
    private final Runnable action;
    private int fixedHeight = Theme.CONTROL_HEIGHT;

    public Button(Supplier<String> label, Runnable action) {
        this.label = label;
        this.action = action;
    }

    public static Button of(String label, Runnable action) {
        return new Button(() -> label, action);
    }

    public Button icon(Icons.Icon value) {
        this.icon = () -> value;
        return this;
    }

    public Button icon(Supplier<Icons.Icon> value) {
        this.icon = value;
        return this;
    }

    public Button variant(Variant value) {
        this.variant = () -> value;
        return this;
    }

    public Button variant(Supplier<Variant> value) {
        this.variant = value;
        return this;
    }

    public Button large() {
        this.fixedHeight = Theme.CONTROL_HEIGHT_LARGE;
        return this;
    }

    /** Natural width for its label and icon. */
    public int naturalWidth(Painter p) {
        int w = p.textWidth(label.get(), 1f, Painter.Weight.SEMIBOLD) + 16;
        return icon.get() == null ? w : w + Theme.ICON_SIZE + 4;
    }

    @Override
    public int preferredHeight(int availableWidth) {
        return fixedHeight;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    protected void renderSelf(Painter p) {
        Variant v = variant.get();
        boolean enabled = isEnabled();
        float h = hover.eased();
        float pr = press.eased();
        int base;
        int hot;
        int down;
        int text;
        switch (v) {
            case PRIMARY -> {
                base = Theme.ACCENT;
                hot = Theme.ACCENT_HOVER;
                down = Theme.ACCENT_PRESSED;
                text = Theme.TEXT_ON_ACCENT;
            }
            case DANGER -> {
                base = 0xFFE5485A;
                hot = 0xFFF05A6B;
                down = 0xFFC93A4B;
                text = Theme.TEXT_ON_ACCENT;
            }
            case LIVE -> {
                base = Theme.LIVE;
                hot = 0xFFFF6170;
                down = 0xFFE03A49;
                text = Theme.TEXT_ON_ACCENT;
            }
            case GHOST -> {
                base = 0x00000000;
                hot = Theme.SURFACE_HOVER;
                down = Theme.SURFACE_PRESSED;
                text = Theme.TEXT_SECONDARY;
            }
            default -> {
                base = Theme.SURFACE_RAISED;
                hot = Theme.SURFACE_HOVER;
                down = Theme.SURFACE_PRESSED;
                text = Theme.TEXT;
            }
        }
        int fill = Theme.mix(Theme.mix(base, hot, h), down, pr);
        if (!enabled) {
            fill = v == Variant.GHOST ? 0 : Theme.withAlpha(Theme.SURFACE_RAISED, 0.6f);
            text = Theme.TEXT_DISABLED;
        }
        p.roundRect(x, y, width, height, Theme.RADIUS, fill);
        if (v == Variant.SECONDARY && enabled) {
            p.roundBorder(x, y, width, height, Theme.RADIUS, 1f, Theme.BORDER);
        }
        String value = label.get();
        Icons.Icon glyph = icon.get();
        int textWidth = value.isEmpty() ? 0 : p.textWidth(value, 1f, Painter.Weight.SEMIBOLD);
        int contentWidth = textWidth + (glyph == null ? 0 : Theme.ICON_SIZE + (value.isEmpty() ? 0 : 4));
        float cx = x + (width - Math.min(contentWidth, width - 8)) / 2f;
        float ty = y + (height - 8) / 2f;
        if (glyph != null) {
            Icons.draw(p, glyph, cx, y + (height - Theme.ICON_SIZE) / 2f, Theme.ICON_SIZE, text);
            cx += Theme.ICON_SIZE + 4;
        }
        p.textClipped(value, cx, ty, width - 8 - (glyph == null ? 0 : Theme.ICON_SIZE + 4), text, 1f, Painter.Weight.SEMIBOLD);
    }

    @Override
    public boolean mouseDown(double mx, double my, int button) {
        if (button != 0 || !isEnabled()) {
            return false;
        }
        pressed = true;
        return true;
    }

    @Override
    public void mouseUp(double mx, double my, int button) {
        boolean inside = contains(mx, my);
        pressed = false;
        if (inside && isEnabled()) {
            action.run();
        }
    }

    @Override
    public boolean activate() {
        if (isEnabled()) {
            action.run();
            return true;
        }
        return false;
    }
}
