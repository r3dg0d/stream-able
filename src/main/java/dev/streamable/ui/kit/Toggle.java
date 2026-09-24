package dev.streamable.ui.kit;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A labelled switch. The whole row is the hit target. */
public final class Toggle extends UiNode {

    private final Supplier<String> label;
    private final BooleanSupplier value;
    private final Consumer<Boolean> setter;
    private final Anim knob = new Anim(Theme.ANIM);
    private Supplier<String> detail = () -> null;

    public Toggle(Supplier<String> label, BooleanSupplier value, Consumer<Boolean> setter) {
        this.label = label;
        this.value = value;
        this.setter = setter;
        knob.snap(value.getAsBoolean() ? 1 : 0);
    }

    public static Toggle of(String label, BooleanSupplier value, Consumer<Boolean> setter) {
        return new Toggle(() -> label, value, setter);
    }

    public Toggle detail(Supplier<String> text) {
        this.detail = text;
        return this;
    }

    @Override
    public int preferredHeight(int availableWidth) {
        return detail.get() == null ? Theme.CONTROL_HEIGHT : Theme.CONTROL_HEIGHT + 9;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    protected boolean showFocusRing() {
        return true;
    }

    @Override
    protected void renderSelf(Painter p) {
        boolean on = value.getAsBoolean();
        float t = knob.update(on ? 1 : 0, p.delta());
        if (hover.value() > 0) {
            p.roundRect(x - 3, y, width + 6, height, Theme.RADIUS, Theme.withAlpha(Theme.SURFACE_HOVER, hover.eased() * 0.7f));
        }
        int labelColor = isEnabled() ? Theme.TEXT : Theme.TEXT_DISABLED;
        p.textClipped(label.get(), x, y + 4, width - 34, labelColor, 1f, Painter.Weight.REGULAR);
        String extra = detail.get();
        if (extra != null) {
            p.textClipped(extra, x, y + 14, width - 34, Theme.TEXT_MUTED, Theme.TEXT_CAPTION, Painter.Weight.REGULAR);
        }
        float tw = 22;
        float th = 12;
        float tx = x + width - tw;
        float ty = y + (Theme.CONTROL_HEIGHT - th) / 2f;
        int track = Theme.mix(0xFF2C3242, Theme.ACCENT, t);
        if (!isEnabled()) {
            track = Theme.withAlpha(track, 0.4f);
        }
        p.roundRect(tx, ty, tw, th, th / 2, track);
        float kx = tx + 2 + t * (tw - th);
        p.circle(kx + (th - 4) / 2f, ty + th / 2f, (th - 4) / 2f, isEnabled() ? 0xFFFFFFFF : 0xFF8890A0);
    }

    @Override
    public boolean mouseDown(double mx, double my, int button) {
        if (button != 0 || !isEnabled()) {
            return false;
        }
        setter.accept(!value.getAsBoolean());
        return true;
    }

    @Override
    public boolean activate() {
        if (isEnabled()) {
            setter.accept(!value.getAsBoolean());
            return true;
        }
        return false;
    }
}
