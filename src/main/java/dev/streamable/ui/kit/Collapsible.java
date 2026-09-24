package dev.streamable.ui.kit;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * An expandable stage row: header with enable switch, summary and chevron;
 * the body (a column of controls) shows only when expanded, so the audio chain
 * never puts fifty sliders on screen at once.
 */
public final class Collapsible extends UiNode {

    private final String title;
    private final Supplier<String> summary;
    private final BooleanSupplier enabledState;
    private final Consumer<Boolean> enabledSetter;
    private final Layouts.Column body;
    private boolean expanded;
    private final Anim open = new Anim(Theme.ANIM);
    private static final int HEADER = 22;

    public Collapsible(String title, Supplier<String> summary, BooleanSupplier enabledState, Consumer<Boolean> enabledSetter) {
        this.title = title;
        this.summary = summary;
        this.enabledState = enabledState;
        this.enabledSetter = enabledSetter;
        this.body = add(new Layouts.Column(Theme.SPACE_4, 0));
    }

    public Layouts.Column body() {
        return body;
    }

    public boolean expanded() {
        return expanded;
    }

    public void setExpanded(boolean value) {
        expanded = value;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    public int preferredHeight(int availableWidth) {
        return expanded ? HEADER + body.preferredHeight(availableWidth - 20) + 12 : HEADER;
    }

    @Override
    protected void layout() {
        body.setBounds(x + 10, y + HEADER + 4, width - 20, body.preferredHeight(width - 20));
    }

    @Override
    public UiNode hit(double mx, double my) {
        if (!isVisible() || !contains(mx, my)) {
            return null;
        }
        if (expanded && my >= y + HEADER) {
            UiNode found = body.hit(mx, my);
            return found != null ? found : null;
        }
        return this;
    }

    @Override
    protected void renderSelf(Painter p) {
        open.update(expanded ? 1 : 0, p.delta());
        boolean on = enabledState == null || enabledState.getAsBoolean();
        boolean headerHot = p.hovered(x, y, width, HEADER);
        p.roundRect(x, y, width, height, Theme.RADIUS, Theme.mix(Theme.SURFACE_RAISED, Theme.SURFACE_HOVER,
                headerHot ? 0.6f : 0));
        Icons.draw(p, expanded ? Icons.Icon.CHEVRON_DOWN : Icons.Icon.CHEVRON_RIGHT, x + 6, y + 7, 8, Theme.TEXT_MUTED);
        p.text(title, x + 18, y + 7, on ? Theme.TEXT : Theme.TEXT_SECONDARY, 1f, Painter.Weight.SEMIBOLD);
        String sum = summary.get();
        int titleWidth = p.textWidth(title, 1f, Painter.Weight.SEMIBOLD);
        if (sum != null) {
            p.textClipped(sum, x + 26 + titleWidth, y + 7, width - titleWidth - 70, Theme.TEXT_MUTED, Theme.TEXT_CAPTION, Painter.Weight.REGULAR);
        }
        if (enabledState != null) {
            float tw = 22;
            float th = 12;
            float tx = x + width - tw - 8;
            float ty = y + 5;
            p.roundRect(tx, ty, tw, th, th / 2, on ? Theme.ACCENT : 0xFF2C3242);
            p.circle(tx + (on ? tw - th / 2f : th / 2f), ty + th / 2f, th / 2f - 2, 0xFFFFFFFF);
        }
    }

    @Override
    protected void renderChildren(Painter p) {
        if (expanded) {
            super.renderChildren(p);
        }
    }

    @Override
    public boolean mouseDown(double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        if (enabledState != null && mx >= x + width - 36 && my < y + HEADER) {
            enabledSetter.accept(!enabledState.getAsBoolean());
            return true;
        }
        expanded = !expanded;
        UiScreen screen = screen();
        if (screen != null) {
            screen.relayout();
        }
        return true;
    }

    @Override
    public boolean activate() {
        expanded = !expanded;
        UiScreen screen = screen();
        if (screen != null) {
            screen.relayout();
        }
        return true;
    }

    @Override
    public void collectFocusable(java.util.List<UiNode> out) {
        if (!isVisible()) {
            return;
        }
        out.add(this);
        if (expanded) {
            body.collectFocusable(out);
        }
    }
}
