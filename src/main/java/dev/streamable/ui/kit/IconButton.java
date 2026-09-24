package dev.streamable.ui.kit;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** A square icon-only button for secondary actions; always carries a tooltip. */
public final class IconButton extends UiNode {

    private final Supplier<Icons.Icon> icon;
    private final Runnable action;
    private BooleanSupplier active = () -> false;
    private int activeColor = Theme.ACCENT;

    public IconButton(Supplier<Icons.Icon> icon, String tooltip, Runnable action) {
        this.icon = icon;
        this.action = action;
        this.tooltip = tooltip;
    }

    public static IconButton of(Icons.Icon icon, String tooltip, Runnable action) {
        return new IconButton(() -> icon, tooltip, action);
    }

    public IconButton activeWhen(BooleanSupplier condition, int color) {
        this.active = condition;
        this.activeColor = color;
        return this;
    }

    @Override
    public int preferredHeight(int availableWidth) {
        return Theme.CONTROL_HEIGHT;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    protected void renderSelf(Painter p) {
        boolean on = active.getAsBoolean();
        int bg = Theme.mix(Theme.mix(0x00000000, Theme.SURFACE_HOVER, hover.eased()), Theme.SURFACE_PRESSED, press.eased());
        if (on) {
            bg = Theme.withAlpha(activeColor, 0.18f + 0.1f * hover.eased());
        }
        p.roundRect(x, y, width, height, Theme.RADIUS, bg);
        int color = !isEnabled() ? Theme.TEXT_DISABLED : on ? activeColor : Theme.mix(Theme.TEXT_SECONDARY, Theme.TEXT, hover.eased());
        float size = Math.min(width, height) - 7;
        Icons.draw(p, icon.get(), x + (width - size) / 2f, y + (height - size) / 2f, size, color);
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
