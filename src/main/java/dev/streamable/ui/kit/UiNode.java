package dev.streamable.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Base of every Studio component: a rectangle with children, layout,
 * input handling and optional keyboard focus.
 *
 * <p>Coordinates are GUI pixels. Layout is explicit and cheap - parents set
 * children's bounds in {@link #layout()} - which keeps behaviour predictable
 * at every window size.</p>
 */
public abstract class UiNode {

    protected int x;
    protected int y;
    protected int width;
    protected int height;
    protected UiNode parent;
    protected final List<UiNode> children = new ArrayList<>();
    protected String tooltip;
    private BooleanSupplier visible = () -> true;
    private BooleanSupplier enabled = () -> true;
    protected final Anim hover = new Anim(Theme.ANIM);
    protected final Anim press = new Anim(Theme.ANIM_FAST);
    protected boolean pressed;

    // ---- tree --------------------------------------------------------------------------

    public <T extends UiNode> T add(T child) {
        child.parent = this;
        children.add(child);
        return child;
    }

    public void clear() {
        children.clear();
    }

    public List<UiNode> children() {
        return children;
    }

    public UiScreen screen() {
        UiNode node = this;
        while (node.parent != null) {
            node = node.parent;
        }
        return node instanceof RootNode root ? root.screen : null;
    }

    // ---- geometry ----------------------------------------------------------------------

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        layout();
    }

    /** Positions children. Default: none. */
    protected void layout() {
    }

    /** Height this node wants at a given width; containers stack by it. */
    public int preferredHeight(int availableWidth) {
        return height > 0 ? height : Theme.CONTROL_HEIGHT;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    // ---- state ------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public <T extends UiNode> T visibleWhen(BooleanSupplier condition) {
        this.visible = condition;
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public <T extends UiNode> T enabledWhen(BooleanSupplier condition) {
        this.enabled = condition;
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public <T extends UiNode> T tooltip(String text) {
        this.tooltip = text;
        return (T) this;
    }

    public boolean isVisible() {
        return visible.getAsBoolean();
    }

    public boolean isEnabled() {
        return enabled.getAsBoolean() && (parent == null || parent.isEnabled());
    }

    public String tooltip() {
        return tooltip;
    }

    public boolean isFocusable() {
        return false;
    }

    public boolean isFocused() {
        UiScreen screen = screen();
        return screen != null && screen.focusedNode() == this;
    }

    public void requestFocus() {
        UiScreen screen = screen();
        if (screen != null) {
            screen.focusNode(this);
        }
    }

    // ---- rendering --------------------------------------------------------------------

    public final void render(Painter p) {
        if (!isVisible()) {
            return;
        }
        boolean over = isEnabled() && p.hovered(x, y, width, height) && screenAllowsHover();
        hover.update(over ? 1 : 0, p.delta());
        press.update(pressed ? 1 : 0, p.delta());
        renderSelf(p);
        renderChildren(p);
        if (isFocused() && showFocusRing()) {
            p.roundBorder(x - 1.5f, y - 1.5f, width + 3, height + 3, Theme.RADIUS + 1.5f, 1f, Theme.FOCUS_RING);
        }
    }

    private boolean screenAllowsHover() {
        UiScreen screen = screen();
        return screen == null || !screen.pointerCapturedElsewhere(this);
    }

    protected void renderSelf(Painter p) {
    }

    protected void renderChildren(Painter p) {
        for (UiNode child : children) {
            child.render(p);
        }
    }

    protected boolean showFocusRing() {
        return true;
    }

    // ---- input: return true when handled ---------------------------------------------

    /** Deepest visible, enabled node under the pointer. */
    public UiNode hit(double mx, double my) {
        if (!isVisible() || !contains(mx, my)) {
            return null;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            UiNode found = children.get(i).hit(mx, my);
            if (found != null) {
                return found;
            }
        }
        return this;
    }

    public boolean mouseDown(double mx, double my, int button) {
        return false;
    }

    public void mouseUp(double mx, double my, int button) {
    }

    public void mouseDrag(double mx, double my) {
    }

    public boolean mouseScroll(double mx, double my, double amount) {
        return false;
    }

    public boolean keyDown(int key, int modifiers) {
        return false;
    }

    public boolean charTyped(int codepoint) {
        return false;
    }

    /** Keyboard activation (Enter / Space). */
    public boolean activate() {
        return false;
    }

    public void onFocusChanged(boolean focused) {
    }

    /** Collects focusable nodes in tree order. */
    public void collectFocusable(List<UiNode> out) {
        if (!isVisible()) {
            return;
        }
        if (isFocusable() && isEnabled()) {
            out.add(this);
        }
        for (UiNode child : children) {
            child.collectFocusable(out);
        }
    }

    /** Root of a screen's tree. */
    static final class RootNode extends UiNode {
        final UiScreen screen;

        RootNode(UiScreen screen) {
            this.screen = screen;
        }

        @Override
        protected void layout() {
            for (UiNode child : children) {
                child.setBounds(x, y, width, height);
            }
        }
    }
}
