package dev.streamable.ui.kit;

import dev.streamable.ui.StreamAbleScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Base for Stream-able's screens built on the UI kit.
 *
 * <p>Owns the component tree, keyboard focus (Tab / Shift+Tab, Enter or Space
 * to activate, arrows inside controls), a popup layer (drop-down lists),
 * tooltips, and the Escape rules: close the popup, then leave the text field,
 * then go back.</p>
 */
public abstract class UiScreen extends Screen implements StreamAbleScreen {

    private final UiNode.RootNode root = new UiNode.RootNode(this);
    private UiNode focused;
    /** Focus rings show only after keyboard navigation, not after a click. */
    private boolean focusVisible;
    private UiNode captured;
    private UiNode popup;
    private long lastFrame = System.nanoTime();
    private UiNode tooltipNode;
    private long tooltipSince;
    private String toast;
    private int toastColor;
    private long toastUntil;

    protected UiScreen(Component title) {
        super(title);
    }

    /** Builds the tree into {@code root}. Called on init and whenever {@link #rebuild()} is requested. */
    protected abstract void build(UiNode root);

    @Override
    protected void init() {
        rebuild();
    }

    /** Re-creates the whole tree (page change). */
    public void rebuild() {
        focusNode(null);   // lets a field being edited commit first
        root.clear();
        popup = null;
        captured = null;
        build(root);
    }

    /** Layout already runs every frame; kept so components can state the intent explicitly. */
    public void relayout() {
    }

    public UiNode focusedNode() {
        return focused;
    }

    public boolean focusVisible() {
        return focusVisible;
    }

    public void focusNode(UiNode node) {
        if (focused == node) {
            return;
        }
        UiNode previous = focused;
        focused = node;
        if (previous != null) {
            previous.onFocusChanged(false);
        }
        if (node != null) {
            node.onFocusChanged(true);
            for (UiNode n = node.parent; n != null; n = n.parent) {
                if (n instanceof ScrollView scroll) {
                    scroll.reveal(node);
                }
            }
        }
    }

    /** True while another node owns the pointer (a drag), so hover effects do not flicker. */
    boolean pointerCapturedElsewhere(UiNode node) {
        return captured != null && captured != node;
    }

    public void openPopup(UiNode node) {
        popup = node;
        popup.parent = root;
        if (node instanceof Dropdown.ListPopup list) {
            list.place(width, height);
        } else if (node instanceof Dialog) {
            node.setBounds(0, 0, width, height);
            List<UiNode> order = new ArrayList<>();
            node.collectFocusable(order);
            focusNode(order.stream().filter(n -> n instanceof TextField).findFirst().orElse(null));
        }
    }

    public void closePopup() {
        if (popup instanceof Dialog && focused != null) {
            for (UiNode n = focused; n != null; n = n.parent) {
                if (n == popup) {
                    focusNode(null);
                    break;
                }
            }
        }
        popup = null;
    }

    public boolean hasDialog() {
        return popup instanceof Dialog;
    }

    public void toast(String message, int color) {
        toast = message;
        toastColor = color;
        toastUntil = System.currentTimeMillis() + 4000;
    }

    public int textWidth(String text) {
        return font.width(Painter.styled(text, Painter.Weight.REGULAR));
    }

    /** Height of wrapped text, without drawing. */
    public int measureParagraph(String text, int width, float scale) {
        int lineHeight = Math.round(9 * scale) + 1;
        int lines = 0;
        for (String raw : text.split("\n")) {
            lines++;
            StringBuilder line = new StringBuilder();
            for (String word : raw.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (textWidth(candidate) * scale > width && !line.isEmpty()) {
                    lines++;
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
        }
        return lines * lineHeight;
    }

    // ---- rendering ----------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float delta = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        // Layout is explicit and cheap, so it runs every frame: rows that
        // appear or disappear with live state never leave stale gaps.
        onFrame();
        root.setBounds(0, 0, width, height);
        if (popup instanceof Dialog) {
            popup.setBounds(0, 0, width, height);
        }
        Painter painter = new Painter(graphics, mouseX, mouseY, delta);
        drawBackdrop(painter);
        root.render(painter);
        if (popup != null) {
            painter.raise();
            popup.render(painter);
        }
        drawTooltip(painter, mouseX, mouseY);
        drawToast(painter);
    }

    /** Per-frame hook before drawing (live data refresh). */
    protected void onFrame() {
    }

    protected void drawBackdrop(Painter painter) {
        painter.fill(0, 0, width, height, Theme.BACKDROP);
    }

    private void drawTooltip(Painter p, int mx, int my) {
        UiNode over = popup instanceof Dialog ? popup.hit(mx, my)
                : popup != null && popup.contains(mx, my) ? null : root.hit(mx, my);
        while (over != null && over.tooltip() == null) {
            over = over.parent;
        }
        if (over != tooltipNode) {
            tooltipNode = over;
            tooltipSince = System.currentTimeMillis();
        }
        if (tooltipNode == null || captured != null || System.currentTimeMillis() - tooltipSince < 450) {
            return;
        }
        String text = tooltipNode.tooltip();
        int w = Math.min(220, p.textWidth(text, Theme.TEXT_CAPTION, Painter.Weight.REGULAR) + 12);
        int h = p.paragraphHeight(text, w - 12, Theme.TEXT_CAPTION) + 8;
        int tx = Math.min(mx + 10, width - w - 4);
        int ty = my + 14 + h > height ? my - h - 6 : my + 14;
        p.raise();
        p.shadow(tx, ty, w, h, Theme.RADIUS, 5, Theme.SHADOW);
        p.roundRect(tx, ty, w, h, Theme.RADIUS, 0xF0262B38);
        p.roundBorder(tx, ty, w, h, Theme.RADIUS, 1f, Theme.BORDER_STRONG);
        p.paragraph(text, tx + 6, ty + 4, w - 12, Theme.TEXT, Theme.TEXT_CAPTION);
    }

    private void drawToast(Painter p) {
        if (toast == null || System.currentTimeMillis() > toastUntil) {
            return;
        }
        int w = Math.min(width - 40, p.textWidth(toast) + 28);
        int x = (width - w) / 2;
        int y = height - Theme.STATUS_BAR_HEIGHT - 30;
        p.raise();
        p.shadow(x, y, w, 20, Theme.RADIUS, 6, Theme.SHADOW);
        p.roundRect(x, y, w, 20, Theme.RADIUS, 0xF0262B38);
        p.circle(x + 10, y + 10, 3, toastColor);
        p.textClipped(toast, x + 18, y + 6, w - 24, Theme.TEXT, 1f, Painter.Weight.REGULAR);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // The kit draws its own backdrop.
    }

    // ---- input -------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x();
        double my = event.y();
        if (popup != null) {
            if (popup instanceof Dialog || popup.contains(mx, my)) {
                dispatchDown(popup, mx, my, event.button());
                return true;
            }
            popup = null;
            return true;
        }
        if (dispatchDown(root, mx, my, event.button())) {
            return true;
        }
        focusNode(null);
        return super.mouseClicked(event, doubled);
    }

    /** Sends a press to the deepest node under the pointer, bubbling up to {@code scope}. */
    private boolean dispatchDown(UiNode scope, double mx, double my, int button) {
        focusVisible = false;
        UiNode target = scope.hit(mx, my);
        for (UiNode node = target; node != null; node = node == scope ? null : node.parent) {
            if (node.isEnabled() && node.mouseDown(mx, my, button)) {
                captured = node;
                if (node.isFocusable()) {
                    focusNode(node);
                } else if (!(focused instanceof TextField) || focused != node) {
                    focusNode(null);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (captured != null) {
            UiNode node = captured;
            captured = null;
            node.mouseUp(event.x(), event.y(), event.button());
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (captured != null) {
            captured.mouseDrag(event.x(), event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (popup instanceof Dialog) {
            return true;
        }
        if (popup != null && popup.contains(mouseX, mouseY)) {
            return popup.mouseScroll(mouseX, mouseY, scrollY);
        }
        for (UiNode node = root.hit(mouseX, mouseY); node != null; node = node.parent) {
            if (node.isEnabled() && node.mouseScroll(mouseX, mouseY, scrollY)) {
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        int mods = event.modifiers();
        if (popup != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                closePopup();
                return true;
            }
            if (popup.keyDown(key, mods)) {
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (focused instanceof TextField) {
                focusNode(null);
                return true;
            }
            onEscape();
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            moveFocus((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1);
            return true;
        }
        if (focused != null) {
            if (focused.keyDown(key, mods)) {
                return true;
            }
            if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE)
                    && !(focused instanceof TextField) && focused.activate()) {
                return true;
            }
        }
        if (handleShortcut(key, mods)) {
            return true;
        }
        return false;
    }

    /** Screen-level shortcuts; return true when handled. */
    protected boolean handleShortcut(int key, int modifiers) {
        return false;
    }

    /** Escape with nothing to dismiss: default closes the screen. */
    protected void onEscape() {
        onClose();
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (focused != null && focused.charTyped(event.codepoint())) {
            return true;
        }
        return super.charTyped(event);
    }

    private void moveFocus(int direction) {
        List<UiNode> order = new ArrayList<>();
        (popup instanceof Dialog ? popup : root).collectFocusable(order);
        if (order.isEmpty()) {
            return;
        }
        int index = focused == null ? -1 : order.indexOf(focused);
        int next = index < 0 ? (direction > 0 ? 0 : order.size() - 1)
                : Math.floorMod(index + direction, order.size());
        focusVisible = true;
        focusNode(order.get(next));
    }

    @Override
    public void removed() {
        focusNode(null);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
