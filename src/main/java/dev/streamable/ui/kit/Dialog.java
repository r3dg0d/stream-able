package dev.streamable.ui.kit;

import org.lwjgl.glfw.GLFW;

/**
 * A modal dialog: a scrim over the screen, a card with a title, a message,
 * optional content (a text field, a list) and a row of actions.
 *
 * <p>Opened with {@link UiScreen#openPopup}. While it is open, clicks and
 * Tab focus stay inside it; Escape cancels and Enter confirms (unless a text
 * field inside has focus, where Enter is also accepted as confirm).</p>
 */
public final class Dialog extends UiNode {

    private static final int WIDTH = 260;

    private final String title;
    private final Layouts.Column card = new Layouts.Column(Theme.SPACE_5, 12);
    private final Runnable onConfirm;
    private int cardHeight;

    private Dialog(String title, String message, UiNode content, String confirmLabel, Button.Variant variant,
                   Runnable onConfirm) {
        this.title = title;
        this.onConfirm = onConfirm;
        add(card);
        card.add(new Layouts.Custom(14, n -> n.painter().text(title, n.x(), n.y() + 2, Theme.TEXT, 1.1f, Painter.Weight.SEMIBOLD)));
        if (message != null && !message.isEmpty()) {
            card.add(new Label(() -> message).color(Theme.TEXT_SECONDARY).wrap());
        }
        if (content != null) {
            card.add(content);
        }
        Layouts.Row actions = card.add(new Layouts.Row(Theme.SPACE_3));
        actions.add(Layouts.spacer(0), -1);
        actions.add(Button.of("Cancel", this::close).variant(Button.Variant.GHOST), 64);
        actions.add(Button.of(confirmLabel, this::confirm).variant(variant), 84);
    }

    /** A yes/no confirmation. */
    public static Dialog confirm(String title, String message, String confirmLabel, boolean destructive,
                                 Runnable onConfirm) {
        return new Dialog(title, message, null, confirmLabel,
                destructive ? Button.Variant.DANGER : Button.Variant.PRIMARY, onConfirm);
    }

    /** A dialog around custom content (for example a {@link TextField}). */
    public static Dialog withContent(String title, String message, UiNode content, String confirmLabel,
                                     Runnable onConfirm) {
        return new Dialog(title, message, content, confirmLabel, Button.Variant.PRIMARY, onConfirm);
    }

    private void close() {
        UiScreen screen = screen();
        if (screen != null) {
            screen.closePopup();
        }
    }

    private void confirm() {
        close();
        onConfirm.run();
    }

    @Override
    protected void layout() {
        int w = Math.min(WIDTH, width - 24);
        cardHeight = card.preferredHeight(w);
        card.setBounds(x + (width - w) / 2, y + Math.max(8, (height - cardHeight) / 2), w, cardHeight);
    }

    @Override
    protected void renderSelf(Painter p) {
        p.fill(x, y, width, height, 0x99000000);
        p.shadow(card.x(), card.y(), card.width(), card.height(), Theme.RADIUS_LARGE, 10, Theme.SHADOW);
        p.roundRect(card.x(), card.y(), card.width(), card.height(), Theme.RADIUS_LARGE, Theme.SURFACE_RAISED);
        p.roundBorder(card.x(), card.y(), card.width(), card.height(), Theme.RADIUS_LARGE, 1f, Theme.BORDER_STRONG);
    }

    @Override
    public boolean mouseDown(double mx, double my, int button) {
        // Clicking the scrim does nothing: dialogs close through their buttons or Escape.
        return true;
    }

    @Override
    public boolean keyDown(int key, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            UiScreen screen = screen();
            UiNode focused = screen == null ? null : screen.focusedNode();
            if (focused == null || focused instanceof TextField) {
                confirm();
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "Dialog[" + title + "]";
    }
}
