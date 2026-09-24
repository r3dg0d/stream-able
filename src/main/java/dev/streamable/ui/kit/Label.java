package dev.streamable.ui.kit;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Static or live text, optionally wrapped. */
public final class Label extends UiNode {

    private final Supplier<String> text;
    private IntSupplier color = () -> Theme.TEXT_SECONDARY;
    private float scale = Theme.TEXT_BODY;
    private Painter.Weight weight = Painter.Weight.REGULAR;
    private boolean wrap;

    public Label(Supplier<String> text) {
        this.text = text;
    }

    public static Label of(String text) {
        return new Label(() -> text);
    }

    public Label color(int value) {
        this.color = () -> value;
        return this;
    }

    public Label color(IntSupplier value) {
        this.color = value;
        return this;
    }

    public Label scale(float value) {
        this.scale = value;
        return this;
    }

    public Label bold() {
        this.weight = Painter.Weight.SEMIBOLD;
        return this;
    }

    public Label wrap() {
        this.wrap = true;
        return this;
    }

    @Override
    public int preferredHeight(int availableWidth) {
        if (!wrap) {
            return Math.round(10 * scale);
        }
        UiScreen screen = screen();
        return screen == null ? 10 : screen.measureParagraph(text.get(), availableWidth, scale);
    }

    @Override
    protected void renderSelf(Painter p) {
        String value = text.get();
        if (value == null) {
            return;
        }
        if (wrap) {
            p.paragraph(value, x, y, width, color.getAsInt(), scale);
        } else {
            p.textClipped(value, x, y + 1, width, color.getAsInt(), scale, weight);
        }
    }
}
