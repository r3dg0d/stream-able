package dev.streamable.ui.kit;

/** Vertically scrolling viewport around one child (usually a Column). */
public final class ScrollView extends UiNode {

    private final UiNode content;
    private double scroll;
    private double target;
    private int contentHeight;
    private boolean draggingBar;
    private double dragOffset;

    public ScrollView(UiNode content) {
        this.content = add(content);
    }

    @Override
    protected void layout() {
        contentHeight = content.preferredHeight(width - 6);
        clampScroll();
        content.setBounds(x, y - (int) Math.round(scroll), width - 6, contentHeight);
    }

    private void clampScroll() {
        double max = Math.max(0, contentHeight - height);
        target = Math.clamp(target, 0, max);
        scroll = Math.clamp(scroll, 0, max);
    }

    public double scrollOffset() {
        return target;
    }

    /** Restores a scroll position (clamped once the content is laid out). */
    public void setScrollOffset(double offset) {
        target = Math.max(0, offset);
        scroll = target;
    }

    /** Scrolls so a node is visible (keyboard focus moving off-screen). */
    public void reveal(UiNode node) {
        int top = node.y - content.y;
        int bottom = top + node.height;
        if (top < target) {
            target = top - 4;
        } else if (bottom > target + height) {
            target = bottom - height + 4;
        }
        clampScroll();
    }

    public boolean isAncestorOf(UiNode node) {
        for (UiNode n = node; n != null; n = n.parent) {
            if (n == this) {
                return true;
            }
        }
        return false;
    }

    @Override
    public UiNode hit(double mx, double my) {
        if (!isVisible() || !contains(mx, my)) {
            return null;
        }
        if (contentHeight > height && mx >= x + width - 6) {
            return this;
        }
        UiNode found = content.hit(mx, my);
        return found != null ? found : this;
    }

    @Override
    protected void renderChildren(Painter p) {
        if (Math.abs(target - scroll) > 0.3) {
            scroll += (target - scroll) * Math.min(1, p.delta() * 16);
            layout();
        } else if (scroll != target) {
            scroll = target;
            layout();
        }
        int measured = content.preferredHeight(width - 6);
        if (measured != contentHeight) {
            layout();
        }
        p.pushClip(x, y, width, height);
        content.render(p);
        p.popClip();
        if (contentHeight > height) {
            float trackH = height - 4;
            float barH = Math.max(16, trackH * height / (float) contentHeight);
            float barY = y + 2 + (float) ((trackH - barH) * scroll / Math.max(1, contentHeight - height));
            boolean hot = p.hovered(x + width - 6, y, 6, height) || draggingBar;
            p.roundRect(x + width - 4, barY, 3, barH, 1.5f, hot ? Theme.BORDER_STRONG : Theme.BORDER);
        }
    }

    @Override
    public boolean mouseScroll(double mx, double my, double amount) {
        if (contentHeight <= height) {
            return false;
        }
        target -= amount * 24;
        clampScroll();
        return true;
    }

    @Override
    public boolean mouseDown(double mx, double my, int button) {
        if (contentHeight > height && mx >= x + width - 6) {
            draggingBar = true;
            dragOffset = my;
            return true;
        }
        return false;
    }

    @Override
    public void mouseDrag(double mx, double my) {
        if (draggingBar) {
            double ratio = contentHeight / (double) height;
            target += (my - dragOffset) * ratio;
            scroll = target;
            dragOffset = my;
            clampScroll();
            layout();
        }
    }

    @Override
    public void mouseUp(double mx, double my, int button) {
        draggingBar = false;
    }
}
