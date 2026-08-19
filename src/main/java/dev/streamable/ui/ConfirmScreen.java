package dev.streamable.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A small confirmation dialog for destructive actions.
 *
 * <p>Deleting a source throws away its URL, transform and custom CSS, which is
 * not something to lose to a misclick while live.</p>
 */
public final class ConfirmScreen extends Screen {

    private final Screen parent;
    private final String question;
    private final String detail;
    private final Runnable onConfirm;

    public ConfirmScreen(Screen parent, String question, String detail, Runnable onConfirm) {
        super(Component.literal(question));
        this.parent = parent;
        this.question = question;
        this.detail = detail;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int centreX = width / 2;
        int y = height / 2 + 12;
        addRenderableWidget(Button.builder(Component.literal("Delete"), b -> onConfirm.run())
                .bounds(centreX - 104, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(centreX + 4, y, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, question, width / 2, height / 2 - 24, 0xFFFFFFFF);
        graphics.centeredText(font, detail, width / 2, height / 2 - 8, 0xFFB0BEC5);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
