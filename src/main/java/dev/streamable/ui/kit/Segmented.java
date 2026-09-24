package dev.streamable.ui.kit;

import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** A segmented control for 2-5 mutually exclusive choices (e.g. Off / Light / Balanced / Strong). */
public final class Segmented extends UiNode {

    private final List<String> options;
    private final IntSupplier selected;
    private final IntConsumer setter;
    private final Anim slide = new Anim(Theme.ANIM);
    private float position = -1;

    public Segmented(List<String> options, IntSupplier selected, IntConsumer setter) {
        this.options = List.copyOf(options);
        this.selected = selected;
        this.setter = setter;
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
        int count = options.size();
        p.roundRect(x, y, width, height, Theme.RADIUS, Theme.FIELD);
        p.roundBorder(x, y, width, height, Theme.RADIUS, 1f, Theme.BORDER);
        float segment = width / (float) count;
        int index = Math.clamp(selected.getAsInt(), 0, count - 1);
        if (position < 0) {
            position = index;
        }
        position += (index - position) * Math.min(1f, p.delta() / Theme.ANIM * 1.6f);
        p.roundRect(x + 2 + position * segment, y + 2, segment - 4, height - 4, Theme.RADIUS - 1,
                isEnabled() ? Theme.ACCENT : Theme.SURFACE_HOVER);
        for (int i = 0; i < count; i++) {
            float sx = x + i * segment;
            boolean hot = p.hovered((int) sx, y, (int) segment, height) && isEnabled();
            int color = i == index ? Theme.TEXT_ON_ACCENT : hot ? Theme.TEXT : Theme.TEXT_SECONDARY;
            if (!isEnabled()) {
                color = Theme.TEXT_DISABLED;
            }
            p.textCentered(p.ellipsize(options.get(i), (int) segment - 6, 1f, Painter.Weight.SEMIBOLD),
                    sx + segment / 2, y + (height - 8) / 2f, color, 1f, Painter.Weight.SEMIBOLD);
        }
    }

    @Override
    public boolean mouseDown(double mx, double my, int button) {
        if (button != 0 || !isEnabled()) {
            return false;
        }
        int index = (int) ((mx - x) / (width / (float) options.size()));
        setter.accept(Math.clamp(index, 0, options.size() - 1));
        return true;
    }

    @Override
    public boolean keyDown(int key, int modifiers) {
        int index = selected.getAsInt();
        if (key == GLFW.GLFW_KEY_LEFT) {
            setter.accept(Math.max(0, index - 1));
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT) {
            setter.accept(Math.min(options.size() - 1, index + 1));
            return true;
        }
        return false;
    }
}
