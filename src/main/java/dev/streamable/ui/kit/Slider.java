package dev.streamable.ui.kit;

import org.lwjgl.glfw.GLFW;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;

/**
 * A labelled slider with its value shown in real units. Drag, scroll, or use
 * the arrow keys (Shift for fine steps). Double-click resets to default.
 */
public final class Slider extends UiNode {

    private final String label;
    private final double min;
    private final double max;
    private final double step;
    private final DoubleSupplier value;
    private final DoubleConsumer setter;
    private DoubleFunction<String> format = v -> String.format(java.util.Locale.ROOT, "%.1f", v);
    private double defaultValue = Double.NaN;
    private boolean dragging;
    private long lastClick;

    public Slider(String label, double min, double max, double step, DoubleSupplier value, DoubleConsumer setter) {
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = value;
        this.setter = setter;
    }

    public Slider format(DoubleFunction<String> formatter) {
        this.format = formatter;
        return this;
    }

    public Slider defaultValue(double v) {
        this.defaultValue = v;
        return this;
    }

    @Override
    public int preferredHeight(int availableWidth) {
        return 24;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    private double fraction() {
        return Math.clamp((value.getAsDouble() - min) / (max - min), 0, 1);
    }

    private void setFromMouse(double mx) {
        double f = Math.clamp((mx - (x + 4)) / Math.max(1, width - 8), 0, 1);
        set(min + f * (max - min));
    }

    private void set(double v) {
        double snapped = Math.round(v / step) * step;
        setter.accept(Math.clamp(snapped, min, max));
    }

    @Override
    protected void renderSelf(Painter p) {
        boolean enabled = isEnabled();
        p.textClipped(label, x, y + 1, width / 2, enabled ? Theme.TEXT_SECONDARY : Theme.TEXT_DISABLED, 1f, Painter.Weight.REGULAR);
        String shown = format.apply(value.getAsDouble());
        int w = p.textWidth(shown, 1f, Painter.Weight.SEMIBOLD);
        p.text(shown, x + width - w, y + 1, enabled ? Theme.TEXT : Theme.TEXT_DISABLED, 1f, Painter.Weight.SEMIBOLD);
        float trackY = y + 15;
        float f = (float) fraction();
        p.roundRect(x + 4, trackY, width - 8, 4, 2, 0xFF262B38);
        p.roundRect(x + 4, trackY, (width - 8) * f, 4, 2, enabled ? Theme.ACCENT : Theme.TEXT_DISABLED);
        float kx = x + 4 + (width - 8) * f;
        float r = 4.5f + 1.0f * Math.max(hover.eased(), dragging ? 1 : 0);
        p.circle(kx, trackY + 2, r, enabled ? 0xFFFFFFFF : 0xFF8890A0);
        if (dragging || hover.value() > 0) {
            p.circle(kx, trackY + 2, r + 3, Theme.withAlpha(Theme.ACCENT, 0.18f * Math.max(hover.eased(), dragging ? 1 : 0)));
        }
    }

    @Override
    public boolean mouseDown(double mx, double my, int button) {
        if (button != 0 || !isEnabled()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastClick < 300 && !Double.isNaN(defaultValue)) {
            set(defaultValue);
            lastClick = 0;
            return true;
        }
        lastClick = now;
        dragging = true;
        setFromMouse(mx);
        return true;
    }

    @Override
    public void mouseDrag(double mx, double my) {
        if (dragging) {
            setFromMouse(mx);
        }
    }

    @Override
    public void mouseUp(double mx, double my, int button) {
        dragging = false;
    }

    @Override
    public boolean mouseScroll(double mx, double my, double amount) {
        if (!isEnabled()) {
            return false;
        }
        set(value.getAsDouble() + Math.signum(amount) * step);
        return true;
    }

    @Override
    public boolean keyDown(int key, int modifiers) {
        double increment = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? step : step * Math.max(1, Math.round((max - min) / step / 50));
        return switch (key) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_DOWN -> {
                set(value.getAsDouble() - increment);
                yield true;
            }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP -> {
                set(value.getAsDouble() + increment);
                yield true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                set(min);
                yield true;
            }
            case GLFW.GLFW_KEY_END -> {
                set(max);
                yield true;
            }
            default -> false;
        };
    }
}
