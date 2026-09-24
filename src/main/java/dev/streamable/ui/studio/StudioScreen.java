package dev.streamable.ui.studio;

import dev.streamable.StreamAbleClient;
import dev.streamable.audio.mic.MicrophoneService;
import dev.streamable.ui.SourceEditorScreen;
import dev.streamable.ui.kit.Button;
import dev.streamable.ui.kit.Dialog;
import dev.streamable.ui.kit.Icons;
import dev.streamable.ui.kit.Layouts;
import dev.streamable.ui.kit.Painter;
import dev.streamable.ui.kit.ScrollView;
import dev.streamable.ui.kit.Theme;
import dev.streamable.ui.kit.UiNode;
import dev.streamable.ui.kit.UiScreen;
import dev.streamable.ui.kit.Widgets;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Stream-able Studio: every setting and control in one screen.
 *
 * <p>Sidebar navigation on the left, a top bar that always carries the
 * record and go-live controls, the page in a scrolling area, and a status bar
 * that summarises the pipeline. Keyboard: Tab / Shift+Tab move focus,
 * Enter or Space activate, Ctrl+1..9 switch pages, Ctrl+R toggles
 * recording, Escape closes popups, then leaves text fields, then closes.</p>
 */
public final class StudioScreen extends UiScreen {

    /** Remembered for the session so reopening the Studio returns to the same page. */
    private static Page lastPage = Page.HOME;

    private final StreamAbleClient client;
    private final Screen parent;
    private final Studio studio;
    private final java.util.Map<Page, Double> scrollPositions = new java.util.EnumMap<>(Page.class);
    private Shell shell;
    private Page page;

    public StudioScreen(StreamAbleClient client, Screen parent) {
        super(Component.translatable("screen.streamable.studio"));
        this.client = client;
        this.parent = parent;
        this.page = lastPage;
        this.studio = new Studio(client, this);
        client.freezeGameForStudio(parent);
    }

    public Page page() {
        return page;
    }

    public void show(Page next) {
        if (next == page) {
            return;
        }
        rememberScroll();
        page = next;
        lastPage = next;
        updateMicrophoneUser();
        rebuild();
    }

    /** Rebuilds the current page after a structural change, keeping its scroll position. */
    public void refresh() {
        rememberScroll();
        rebuild();
    }

    private void rememberScroll() {
        if (shell != null) {
            scrollPositions.put(page, shell.content.scrollOffset());
        }
    }

    @Override
    protected void init() {
        rememberScroll();   // init also runs on window resize
        super.init();
        updateMicrophoneUser();
    }

    /**
     * Home and Audio show live microphone meters, so while one of them is open
     * the microphone runs even when nothing is recording - but only when the
     * player has enabled it. It is released on every other page and on close.
     */
    public void updateMicrophoneUser() {
        if ((page == Page.AUDIO || page == Page.HOME) && client.config().recording.captureMicrophone) {
            client.microphone().acquire(MicrophoneService.User.STUDIO);
        } else {
            client.microphone().release(MicrophoneService.User.STUDIO);
        }
    }

    @Override
    protected void build(UiNode root) {
        shell = root.add(new Shell());
    }

    @Override
    protected void onFrame() {
        // Keeps the program canvas composed while the Studio is open so the
        // preview and the output framing overlays are live.
        client.video().requestPreview();
    }

    @Override
    protected boolean handleShortcut(int key, int modifiers) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (ctrl && key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
            int index = key - GLFW.GLFW_KEY_1;
            if (index < Page.values().length) {
                show(Page.values()[index]);
                return true;
            }
        }
        if (ctrl && key == GLFW.GLFW_KEY_0) {
            show(Page.values()[Page.values().length - 1]);
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_R) {
            studio.toggleRecording();
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        client.microphone().release(MicrophoneService.User.STUDIO);
        client.microphone().monitor().stop();
        client.config().microphone.monitoring = false;
        client.saveNow();
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        // Also reached when another mod or the game replaces the screen.
        client.microphone().release(MicrophoneService.User.STUDIO);
        super.removed();
    }

    // ---- shell ------------------------------------------------------------------------

    private boolean compactNav() {
        return width < 560;
    }

    /** Positions the sidebar, top bar, page and status bar. */
    private final class Shell extends UiNode {
        private final Sidebar sidebar = add(new Sidebar());
        private final TopBar topBar = add(new TopBar());
        private final ScrollView content;
        private final StatusBar statusBar = add(new StatusBar());

        Shell() {
            Layouts.Column column = new Layouts.Column(Theme.SECTION_GAP, Theme.SPACE_6);
            Pages.build(page, studio, column);
            content = add(new ScrollView(column));
            content.setScrollOffset(scrollPositions.getOrDefault(page, 0.0));
        }

        @Override
        protected void layout() {
            int nav = compactNav() ? 30 : Theme.SIDEBAR_WIDTH;
            sidebar.setBounds(x, y, nav, height - Theme.STATUS_BAR_HEIGHT);
            topBar.setBounds(x + nav, y, width - nav, Theme.TOP_BAR_HEIGHT + 8);
            content.setBounds(x + nav, y + Theme.TOP_BAR_HEIGHT + 8, width - nav,
                    height - Theme.TOP_BAR_HEIGHT - 8 - Theme.STATUS_BAR_HEIGHT);
            statusBar.setBounds(x, y + height - Theme.STATUS_BAR_HEIGHT, width, Theme.STATUS_BAR_HEIGHT);
        }
    }

    /** Page navigation. Collapses to icons on narrow windows. */
    private final class Sidebar extends UiNode {
        Sidebar() {
            for (Page p : Page.values()) {
                add(new NavItem(p));
            }
            add(new NavButton(Icons.Icon.GRID, "Canvas editor",
                    "Arrange sources directly over the game (F7).",
                    () -> minecraft.setScreen(new SourceEditorScreen(client))));
            add(new NavButton(Icons.Icon.CLOSE, "Close", "Close the Studio (Esc).", StudioScreen.this::onClose));
        }

        @Override
        protected void layout() {
            int items = Page.values().length + 2;
            int itemH = Math.clamp((height - 30 - 12) / items - 2, 11, 18);
            int cy = y + 30;
            for (UiNode child : children) {
                if (child instanceof NavItem) {
                    child.setBounds(x + 4, cy, width - 8, itemH);
                    cy += itemH + 2;
                }
            }
            int by = y + height - 6 - itemH * 2 - 2;
            for (UiNode child : children) {
                if (child instanceof NavButton) {
                    child.setBounds(x + 4, by, width - 8, itemH);
                    by += itemH + 2;
                }
            }
        }

        @Override
        protected void renderSelf(Painter p) {
            p.fill(x, y, width, height, Theme.SURFACE);
            p.fill(x + width - 1, y, 1, height, Theme.DIVIDER);
            if (compactNav()) {
                p.circle(x + width / 2f, y + 14, 5, Theme.ACCENT);
            } else {
                p.circle(x + 14, y + 14, 4, Theme.ACCENT);
                p.text("Stream-able", x + 23, y + 10, Theme.TEXT, 1f, Painter.Weight.SEMIBOLD);
            }
        }
    }

    private final class NavItem extends UiNode {
        private final Page target;

        NavItem(Page target) {
            this.target = target;
            tooltip(compactNav() ? target.title() + " - " + target.subtitle() : target.subtitle());
        }

        @Override
        public boolean isFocusable() {
            return true;
        }

        @Override
        protected void renderSelf(Painter p) {
            boolean active = target == page;
            int bg = active ? Theme.ACCENT_SOFT : Theme.withAlpha(Theme.SURFACE_HOVER, hover.eased());
            p.roundRect(x, y, width, height, Theme.RADIUS, bg);
            if (active) {
                p.roundRect(x, y + 4, 2, height - 8, 1, Theme.ACCENT);
            }
            int color = active ? Theme.TEXT : Theme.mix(Theme.TEXT_SECONDARY, Theme.TEXT, hover.eased());
            if (compactNav()) {
                Icons.draw(p, target.icon(), x + (width - 8) / 2f, y + (height - 8) / 2f, 8, color);
            } else {
                Icons.draw(p, target.icon(), x + 7, y + (height - 8) / 2f, 8, color);
                p.textClipped(target.title(), x + 21, y + (height - 8) / 2f, width - 34, color, 1f,
                        active ? Painter.Weight.SEMIBOLD : Painter.Weight.REGULAR);
            }
            int badge = badgeColor();
            if (badge != 0) {
                p.circle(x + width - 7, y + height / 2f, 2.5f, badge);
            }
        }

        /** Small status dots on the pages that need attention. */
        private int badgeColor() {
            return switch (target) {
                case HEALTH -> switch (client.healthReport().worst()) {
                    case CRITICAL -> Theme.DANGER;
                    case WARNING -> Theme.WARNING;
                    default -> client.streaming().isLive() ? Theme.SUCCESS : 0;
                };
                case RUNTIME -> studio.runtimesNeedAttention() ? Theme.WARNING : 0;
                case DESTINATIONS -> client.streamTests().isRunning() ? Theme.INFO : 0;
                default -> 0;
            };
        }

        @Override
        public boolean mouseDown(double mx, double my, int button) {
            if (button == 0) {
                show(target);
                return true;
            }
            return false;
        }

        @Override
        public boolean activate() {
            show(target);
            return true;
        }
    }

    private final class NavButton extends UiNode {
        private final Icons.Icon icon;
        private final String label;
        private final Runnable action;

        NavButton(Icons.Icon icon, String label, String help, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.action = action;
            tooltip(help);
        }

        @Override
        public boolean isFocusable() {
            return true;
        }

        @Override
        protected void renderSelf(Painter p) {
            p.roundRect(x, y, width, height, Theme.RADIUS, Theme.withAlpha(Theme.SURFACE_HOVER, hover.eased()));
            int color = Theme.mix(Theme.TEXT_MUTED, Theme.TEXT, hover.eased());
            if (compactNav()) {
                Icons.draw(p, icon, x + (width - 8) / 2f, y + (height - 8) / 2f, 8, color);
            } else {
                Icons.draw(p, icon, x + 7, y + (height - 8) / 2f, 8, color);
                p.textClipped(label, x + 21, y + (height - 8) / 2f, width - 26, color, 1f, Painter.Weight.REGULAR);
            }
        }

        @Override
        public boolean mouseDown(double mx, double my, int button) {
            if (button == 0) {
                action.run();
                return true;
            }
            return false;
        }

        @Override
        public boolean activate() {
            action.run();
            return true;
        }
    }

    /** Page title on the left; recording and live state with their controls on the right. */
    private final class TopBar extends UiNode {
        private final Button record;
        private final Button live;

        TopBar() {
            record = add(new Button(() -> client.recording().isActive() ? "Stop" : "Record", studio::toggleRecording)
                    .icon(() -> client.recording().isActive() ? Icons.Icon.STOP : Icons.Icon.RECORD)
                    .variant(() -> client.recording().isActive() ? Button.Variant.DANGER : Button.Variant.SECONDARY)
                    .tooltip("Start or stop a local recording (Ctrl+R)."));
            live = add(new Button(() -> client.streaming().isLive() ? "End Stream" : "Go Live", studio::toggleStreaming)
                    .icon(Icons.Icon.LIVE)
                    .variant(() -> client.streaming().isLive() ? Button.Variant.DANGER : Button.Variant.LIVE)
                    .tooltip("Start or end the broadcast to every enabled destination."));
        }

        @Override
        protected void layout() {
            int by = y + (height - Theme.CONTROL_HEIGHT_LARGE) / 2;
            int liveW = 76;
            int recW = 62;
            live.setBounds(x + width - Theme.SPACE_6 - liveW, by, liveW, Theme.CONTROL_HEIGHT_LARGE);
            record.setBounds(live.x() - Theme.SPACE_3 - recW, by, recW, Theme.CONTROL_HEIGHT_LARGE);
        }

        @Override
        protected void renderSelf(Painter p) {
            p.fill(x, y + height - 1, width, 1, Theme.DIVIDER);
            int right = record.x() - Theme.SPACE_4;
            int pills = right;
            if (client.streaming().isLive()) {
                String text = "LIVE " + client.health().formattedUptime();
                pills -= p.textWidth(text, Theme.TEXT_CAPTION, Painter.Weight.SEMIBOLD) + 18;
                Widgets.StatusPill.drawPill(p, pills, y + (height - 13) / 2, text, Theme.LIVE, true);
                pills -= Theme.SPACE_3;
            }
            if (client.recording().isActive()) {
                String text = (client.recording().state() == dev.streamable.recording.RecordingController.State.PAUSED
                        ? "PAUSED " : "REC ") + Studio.clock(client.recording().elapsedMillis());
                pills -= p.textWidth(text, Theme.TEXT_CAPTION, Painter.Weight.SEMIBOLD) + 18;
                Widgets.StatusPill.drawPill(p, pills, y + (height - 13) / 2, text, Theme.RECORDING, true);
            }
            int titleMax = Math.max(40, pills - x - Theme.SPACE_6 - 6);
            p.textClipped(page.title(), x + Theme.SPACE_6, y + 7, titleMax, Theme.TEXT, Theme.TEXT_TITLE,
                    Painter.Weight.SEMIBOLD);
            p.textClipped(page.subtitle(), x + Theme.SPACE_6, y + 20, titleMax, Theme.TEXT_MUTED,
                    Theme.TEXT_CAPTION, Painter.Weight.REGULAR);
        }
    }

    /** One line summarising the whole pipeline. */
    private final class StatusBar extends UiNode {
        @Override
        protected void renderSelf(Painter p) {
            p.fill(x, y, width, height, Theme.SURFACE);
            p.fill(x, y, width, 1, Theme.DIVIDER);
            List<Studio.StatusItem> items = studio.statusItems();
            int cx = x + Theme.SPACE_4;
            int cy = y + (height - 8) / 2;
            for (Studio.StatusItem item : items) {
                int w = p.textWidth(item.text(), Theme.TEXT_CAPTION, Painter.Weight.REGULAR) + 10;
                if (cx + w > x + width - 4) {
                    break;   // lower-priority items drop off first on narrow windows
                }
                p.circle(cx + 2.5f, cy + 3.5f, 2f, item.color());
                p.text(item.text(), cx + 8, cy + 1, Theme.TEXT_SECONDARY, Theme.TEXT_CAPTION, Painter.Weight.REGULAR);
                cx += w + Theme.SPACE_5;
            }
        }
    }

    /** Opens a confirmation dialog. */
    public void confirm(String title, String message, String action, boolean destructive, Runnable onConfirm) {
        openPopup(Dialog.confirm(title, message, action, destructive, onConfirm));
    }
}
