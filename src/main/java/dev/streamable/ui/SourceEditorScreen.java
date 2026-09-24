package dev.streamable.ui;

import dev.streamable.StreamAbleClient;
import dev.streamable.browser.BrowserHandle;
import dev.streamable.browser.input.BrowserKeyboardCompat;
import dev.streamable.compositor.ProgramCanvas;
import dev.streamable.input.EditorMode;
import dev.streamable.source.BrowserSource;
import dev.streamable.source.transform.Point2;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The overlay editing screen: OBS-style transform, or direct browser interaction.
 *
 * <p>Making this a real {@link Screen} is what keeps the input rules honest.
 * While it is open, Minecraft routes input here instead of to gameplay, so a
 * click can never both drag an overlay and swing a pickaxe. While it is closed,
 * browser sources receive no input at all and the game behaves exactly as it
 * would without the mod.</p>
 *
 * <p>The transform box itself is drawn by the compositor at the end of the game
 * render pass, not here: it has to line up with the browser textures, which are
 * composited in the same pass and the same coordinate space.</p>
 */
public final class SourceEditorScreen extends Screen implements StreamAbleScreen {

    private final StreamAbleClient runtime;
    private EditorMode mode = EditorMode.TRANSFORM;
    private boolean dragging;

    private boolean draggingHud;
    private double hudGrabX;
    private double hudGrabY;

    public SourceEditorScreen(StreamAbleClient runtime) {
        super(Component.translatable("screen.streamable.source_editor"));
        this.runtime = runtime;
        runtime.freezeGameForStudio(net.minecraft.client.Minecraft.getInstance().screen);
    }

    @Override
    protected void init() {
        addRenderableWidget(net.minecraft.client.gui.components.Button
                .builder(Component.translatable("streamable.editor.mode", mode.displayName()), b -> toggleMode())
                .bounds(8, 8, 130, 20).build());
        addRenderableWidget(net.minecraft.client.gui.components.Button
                .builder(Component.translatable("streamable.editor.open_studio"),
                        b -> minecraft.setScreen(new dev.streamable.ui.studio.StudioScreen(runtime, this)))
                .bounds(146, 8, 110, 20).build());
    }

    private void toggleMode() {
        setMode(mode.toggled());
        rebuildWidgets();
    }

    private void setMode(EditorMode newMode) {
        if (mode == newMode) {
            return;
        }
        // Dropping focus on the way out stops Chromium from believing it still
        // owns the keyboard once the player is back to transforming.
        if (mode == EditorMode.INTERACT) {
            withSelectedBrowser(handle -> handle.setFocus(false));
        }
        mode = newMode;
        if (mode == EditorMode.INTERACT) {
            withSelectedBrowser(handle -> {
                handle.setFocus(true);
                // Hand the page the host clipboard so the paste fallback has
                // something to insert if Chromium's own paste does nothing.
                handle.setClipboardHint(minecraft.keyboardHandler.getClipboard());
            });
        }
    }

    public EditorMode mode() {
        return mode;
    }

    private void withSelectedBrowser(java.util.function.Consumer<BrowserHandle> action) {
        BrowserSource source = runtime.editor().selected();
        if (source == null) {
            return;
        }
        BrowserHandle handle = runtime.browsers().handleFor(source.id());
        if (handle != null) {
            action.accept(handle);
        }
    }

    /** Mouse position in canvas coordinates, via the GUI-scaled mapping. */
    private Point2 toCanvas(double mouseX, double mouseY) {
        ProgramCanvas.Mapping mapping = runtime.canvas().mappingTo(width, height);
        return mapping.toCanvas(new Point2(mouseX, mouseY));
    }

    /** Mouse position inside the selected source, in its own pixels. */
    private Point2 toSourceLocal(BrowserSource source, double mouseX, double mouseY) {
        return source.transform().canvasToLocal(toCanvas(mouseX, mouseY));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        if (mode == EditorMode.INTERACT) {
            BrowserSource source = runtime.editor().selected();
            if (source != null && source.transform().contains(toCanvas(event.x(), event.y()))) {
                Point2 local = toSourceLocal(source, event.x(), event.y());
                BrowserHandle handle = runtime.browsers().handleFor(source.id());
                if (handle != null) {
                    handle.mousePressed(local.x(), local.y(), event.button(), event.modifiers(), doubled);
                    return true;
                }
            }
            return false;
        }
        int[] hud = StreamHud.bounds();
        if (event.button() == 0 && hud[2] > 0 && event.x() >= hud[0] && event.x() < hud[0] + hud[2]
                && event.y() >= hud[1] && event.y() < hud[1] + hud[3]) {
            // The stream HUD sits above the canvas: dragging it moves the HUD, not a source.
            draggingHud = true;
            hudGrabX = event.x() - hud[0];
            hudGrabY = event.y() - hud[1];
            return true;
        }
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        dragging = runtime.editor().onMousePress(toCanvas(event.x(), event.y()), shift);
        return dragging;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (mode == EditorMode.INTERACT) {
            BrowserSource source = runtime.editor().selected();
            if (source != null) {
                Point2 local = toSourceLocal(source, event.x(), event.y());
                BrowserHandle handle = runtime.browsers().handleFor(source.id());
                if (handle != null) {
                    handle.mouseMoved(local.x(), local.y());
                    return true;
                }
            }
            return false;
        }
        if (draggingHud) {
            StreamHud.moveTo(runtime.config().ui, (int) Math.round(event.x() - hudGrabX),
                    (int) Math.round(event.y() - hudGrabY), width, height);
            return true;
        }
        if (dragging) {
            boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
            runtime.editor().onMouseDrag(toCanvas(event.x(), event.y()), shift);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (mode == EditorMode.INTERACT) {
            BrowserSource source = runtime.editor().selected();
            if (source != null) {
                Point2 local = toSourceLocal(source, event.x(), event.y());
                BrowserHandle handle = runtime.browsers().handleFor(source.id());
                if (handle != null) {
                    handle.mouseReleased(local.x(), local.y(), event.button(), event.modifiers());
                    return true;
                }
            }
        } else if (draggingHud) {
            draggingHud = false;
            runtime.markDirty();
            return true;
        } else if (dragging) {
            dragging = false;
            runtime.editor().onMouseRelease();
            runtime.markDirty();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (mode == EditorMode.INTERACT) {
            BrowserSource source = runtime.editor().selected();
            if (source != null) {
                Point2 local = toSourceLocal(source, mouseX, mouseY);
                BrowserHandle handle = runtime.browsers().handleFor(source.id());
                if (handle != null) {
                    handle.mouseMoved(local.x(), local.y());
                }
            }
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mode == EditorMode.INTERACT) {
            BrowserSource source = runtime.editor().selected();
            if (source != null) {
                Point2 local = toSourceLocal(source, mouseX, mouseY);
                BrowserHandle handle = runtime.browsers().handleFor(source.id());
                if (handle != null) {
                    handle.mouseScrolled(local.x(), local.y(), scrollY);
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Escape always returns control to the player: never trap them inside
        // browser focus.
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (mode == EditorMode.INTERACT) {
                setMode(EditorMode.TRANSFORM);
                rebuildWidgets();
                return true;
            }
            onClose();
            return true;
        }
        if (mode == EditorMode.INTERACT) {
            BrowserSource source = runtime.editor().selected();
            BrowserHandle handle = source == null ? null : runtime.browsers().handleFor(source.id());
            if (handle != null) {
                if (BrowserKeyboardCompat.isPasteShortcut(event.key(), event.modifiers())) {
                    handle.setClipboardHint(minecraft.keyboardHandler.getClipboard());
                }
                handle.keyPressed(event.key(), event.scancode(), event.modifiers());
                return true;
            }
            return false;
        }
        if (runtime.editor().onArrowKey(event.key(),
                (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0)) {
            runtime.markDirty();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_DELETE && runtime.editor().selected() != null) {
            runtime.removeSource(runtime.editor().selectedId());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (mode == EditorMode.INTERACT) {
            BrowserSource source = runtime.editor().selected();
            BrowserHandle handle = source == null ? null : runtime.browsers().handleFor(source.id());
            if (handle != null) {
                handle.keyReleased(event.key(), event.scancode(), event.modifiers());
                return true;
            }
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (mode == EditorMode.INTERACT) {
            BrowserSource source = runtime.editor().selected();
            BrowserHandle handle = source == null ? null : runtime.browsers().handleFor(source.id());
            if (handle != null) {
                handle.charTyped(event.codepoint());
                return true;
            }
        }
        return super.charTyped(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        BrowserSource selected = runtime.editor().selected();
        String hint = selected == null
                ? "Click a browser source to select it. Add sources in the Studio."
                : (mode == EditorMode.TRANSFORM
                ? "Drag to move | handle = resize | Shift+handle = free stretch | knob = rotate | Del = delete"
                : "Interacting with '" + selected.name() + "' - Esc returns to Transform");
        graphics.text(font, hint, 8, height - 18, 0xFFE0E0E0);
        if (selected != null) {
            String numbers = String.format(java.util.Locale.ROOT,
                    "%s   PosX %.0f  PosY %.0f  W %.0f  H %.0f  Rot %.1f",
                    selected.name(), selected.transform().x(), selected.transform().y(),
                    selected.transform().width(), selected.transform().height(),
                    selected.transform().rotation());
            graphics.text(font, numbers, 8, height - 32, 0xFFFFC107);
        }
    }

    /**
     * Fully transparent background.
     *
     * <p>The default screen background dims and blurs the world, which would
     * defeat the point: the editor exists to position overlays against the live
     * game, so the game and the composited browser sources must stay untouched
     * underneath.</p>
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Intentionally empty - no dim, no blur.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        setMode(EditorMode.TRANSFORM);
        runtime.markDirty();
        runtime.saveNow();
        super.onClose();
    }
}
