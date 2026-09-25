package dev.streamable.ui;

import dev.streamable.StreamAbleClient;
import dev.streamable.audio.dsp.LevelMeter;
import dev.streamable.config.InterfaceSettings;
import dev.streamable.diagnostics.HealthReport;
import dev.streamable.streaming.StreamHealth;
import dev.streamable.ui.kit.Painter;
import dev.streamable.ui.kit.Theme;
import dev.streamable.ui.kit.Widgets;
import dev.streamable.ui.studio.Studio;
import com.mojang.blaze3d.platform.Window;
import dev.streamable.mixin.GameRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The compact stream HUD: a small panel on the player's own screen showing
 * LIVE / REC state and, while live, bitrate, encoder FPS, dropped frames and
 * the network condition. Detailed mode adds the encoder, each destination and
 * a microphone meter.
 *
 * <p>It never reaches recordings or streams. Minecraft's own HUD is drawn
 * before Stream-able captures the frame, so a normal HUD element would be
 * captured too; instead this panel is drawn in a second, HUD-only GUI pass
 * after capture, on the player's screen only. It is movable - drag it in the
 * canvas editor, where it is always shown so it can be placed - and its
 * position is saved as a fraction of the screen, so it stays put across window
 * sizes, including ultrawide. By default it sits top-left, clear of the game's
 * toasts.</p>
 */
public final class StreamHud {

    private static final int PAD = 5;
    private static final int MARGIN = 6;
    private static final int DEFAULT_Y = 34;

    /** Last drawn bounds in GUI pixels, for dragging in the canvas editor. */
    private static volatile int[] lastBounds = new int[4];
    private static volatile String flashText;
    private static volatile int flashColor;
    private static volatile long flashUntilNanos;

    /** Shows a short message on the HUD for a few seconds (e.g. "Clip saved"); never in outputs. */
    public static void flash(String text, int color) {
        flashText = text;
        flashColor = color;
        flashUntilNanos = System.nanoTime() + 4_000_000_000L;
    }

    private static String activeFlash() {
        return flashText != null && System.nanoTime() < flashUntilNanos ? flashText : null;
    }

    private StreamHud() {
    }

    /**
     * Draws the HUD over the finished frame. Called from the end of the game's
     * render pass, after the program frame has been captured. Hidden while any
     * other screen is open (except the canvas editor, where it can be dragged)
     * and when the GUI is hidden with F1.
     */
    public static void renderAfterCapture(GameRenderer gameRenderer) {
        Minecraft mc = Minecraft.getInstance();
        StreamAbleClient client = StreamAbleClient.get();
        boolean editing = mc.screen instanceof SourceEditorScreen;
        if (client == null || mc.player == null || mc.options.hideGui || (mc.screen != null && !editing)) {
            lastBounds = new int[4];
            return;
        }
        InterfaceSettings ui = client.config().ui;
        StreamHealth health = client.health();
        boolean recording = client.recording().isActive();
        boolean replay = client.replayBuffer().isRunning();
        if (!editing && activeFlash() == null && (!ui.showStreamHud || (!health.live() && !recording && !replay))) {
            lastBounds = new int[4];
            return;
        }
        GuiRenderState state = gameRenderer.getGameRenderState().guiRenderState;
        state.reset();   // the frame's own GUI has already been drawn
        Window window = mc.getWindow();
        GuiGraphicsExtractor graphics = new GuiGraphicsExtractor(mc, state,
                (int) mc.mouseHandler.getScaledXPos(window), (int) mc.mouseHandler.getScaledYPos(window));
        extract(graphics, client, health, recording, editing);

        GameRendererAccessor access = (GameRendererAccessor) gameRenderer;
        access.streamable$setUseUiLightmap(true);
        try {
            GuiRenderer renderer = access.streamable$guiRenderer();
            renderer.render(access.streamable$fogRenderer().getBuffer(FogRenderer.FogMode.NONE));
            renderer.endFrame();
        } finally {
            access.streamable$setUseUiLightmap(false);
        }
    }

    private static void extract(GuiGraphicsExtractor graphics, StreamAbleClient client, StreamHealth health,
                                boolean recording, boolean editing) {
        InterfaceSettings ui = client.config().ui;
        Painter p = new Painter(graphics, -1, -1, 0);
        List<Row> rows = rows(client, health, recording, ui.detailedStreamHud, editing);

        float scale = ui.streamHudScale;
        int w = 0;
        int h = PAD * 2 - 3;
        for (Row row : rows) {
            w = Math.max(w, row.width(p));
            h += row.height() + 3;
        }
        w += PAD * 2;
        int sw = Math.round(w * scale);
        int sh = Math.round(h * scale);
        int freeW = Math.max(0, graphics.guiWidth() - sw - 2 * MARGIN);
        int freeH = Math.max(0, graphics.guiHeight() - sh - 2 * MARGIN);
        float fx = ui.streamHudX < 0 ? 0f : ui.streamHudX;
        float fy = Math.max(0f, ui.streamHudY);
        int x = MARGIN + Math.round(freeW * fx);
        // Unplaced, it sits just below the canvas editor's button row, so it
        // never covers those buttons when the editor is open.
        int y = ui.streamHudY < 0 ? Math.min(DEFAULT_Y, MARGIN + freeH) : MARGIN + Math.round(freeH * fy);
        lastBounds = new int[]{x, y, sw, sh};

        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        p.roundRect(0, 0, w, h, Theme.RADIUS, Theme.withAlpha(0xFF0E1016, ui.streamHudOpacity));
        p.roundBorder(0, 0, w, h, Theme.RADIUS, 1f, Theme.withAlpha(0x33FFFFFF, ui.streamHudOpacity));
        int cy = PAD;
        for (Row row : rows) {
            row.draw(p, PAD, cy, w - 2 * PAD);
            cy += row.height() + 3;
        }
        graphics.pose().popMatrix();
    }

    /** Where the HUD was last drawn: x, y, width, height (all zero when hidden). */
    public static int[] bounds() {
        return lastBounds;
    }

    /** Moves the HUD so its top-left sits at (x, y), stored as fractions of the free area. */
    public static void moveTo(InterfaceSettings ui, int x, int y, int guiWidth, int guiHeight) {
        int[] b = lastBounds;
        int freeW = Math.max(1, guiWidth - b[2] - 2 * MARGIN);
        int freeH = Math.max(1, guiHeight - b[3] - 2 * MARGIN);
        ui.streamHudX = Math.clamp((x - MARGIN) / (float) freeW, 0f, 1f);
        ui.streamHudY = Math.clamp((y - MARGIN) / (float) freeH, 0f, 1f);
    }

    // ---- rows ------------------------------------------------------------------------------

    private interface Row {
        int width(Painter p);

        int height();

        void draw(Painter p, int x, int y, int width);
    }

    private record Pills(List<String> labels, List<Integer> colors) implements Row {
        @Override
        public int width(Painter p) {
            int w = 0;
            for (String label : labels) {
                w += p.textWidth(label, Theme.TEXT_CAPTION, Painter.Weight.SEMIBOLD) + 18 + 4;
            }
            return w - 4;
        }

        @Override
        public int height() {
            return 13;
        }

        @Override
        public void draw(Painter p, int x, int y, int width) {
            int cx = x;
            for (int i = 0; i < labels.size(); i++) {
                cx += Widgets.StatusPill.drawPill(p, cx, y, labels.get(i), colors.get(i), true) + 4;
            }
        }
    }

    private record Text(String text, int color, float scale) implements Row {
        @Override
        public int width(Painter p) {
            return p.textWidth(text, scale, Painter.Weight.REGULAR);
        }

        @Override
        public int height() {
            return Math.round(9 * scale);
        }

        @Override
        public void draw(Painter p, int x, int y, int width) {
            p.text(text, x, y, color, scale, Painter.Weight.REGULAR);
        }
    }

    private record MicMeter(LevelMeter.Reading reading, boolean muted) implements Row {
        @Override
        public int width(Painter p) {
            return 90;
        }

        @Override
        public int height() {
            return 7;
        }

        @Override
        public void draw(Painter p, int x, int y, int width) {
            p.text(muted ? "MIC MUTED" : "MIC", x, y, muted ? Theme.DANGER : Theme.TEXT_MUTED, 0.65f, Painter.Weight.SEMIBOLD);
            int bx = x + (muted ? 44 : 18);
            int bw = width - (bx - x);
            p.roundRect(bx, y + 1, bw, 4, 2, 0xFF1E2230);
            double db = reading.peakDb();
            float f = (float) Math.clamp((db + 60) / 60.0, 0, 1);
            int color = muted ? Theme.TEXT_DISABLED : db > -6 ? Theme.DANGER : db > -18 ? Theme.WARNING : Theme.SUCCESS;
            p.roundRect(bx, y + 1, bw * f, 4, 2, color);
        }
    }

    private static List<Row> rows(StreamAbleClient client, StreamHealth health, boolean recording, boolean detailed,
                                  boolean editing) {
        List<Row> rows = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (health.live()) {
            // LIVE only once a destination has confirmed; otherwise CONNECTING.
            boolean connected = health.liveDestinationCount() > 0;
            labels.add((connected ? "LIVE " : "CONNECTING ") + health.formattedUptime());
            colors.add(connected ? Theme.LIVE : Theme.WARNING);
        }
        if (recording) {
            labels.add("REC " + Studio.clock(client.recording().elapsedMillis()));
            colors.add(Theme.RECORDING);
        }
        if (client.replayBuffer().isRunning()) {
            labels.add("REPLAY " + Studio.clock(client.replayBuffer().configuredSeconds() * 1000L));
            colors.add(Theme.INFO);
        }
        if (labels.isEmpty()) {
            labels.add(editing ? "HUD - drag to move" : "Idle");
            colors.add(Theme.TEXT_MUTED);
        }
        rows.add(new Pills(labels, colors));
        String flash = activeFlash();
        if (flash != null) {
            rows.add(new Text(flash, flashColor, 0.8f));
        }

        if (health.live()) {
            HealthReport.Condition network = client.healthReport().network();
            rows.add(new Text(String.format(Locale.ROOT, "%s kbps · %s FPS · %d dropped · %s",
                    health.outputKbps() < 0 ? "-" : String.format(Locale.ROOT, "%,d", Math.round(health.outputKbps())),
                    health.encodeFps() < 0 ? "-" : Long.toString(Math.round(health.encodeFps())),
                    health.framesDropped(), network.label()),
                    network == HealthReport.Condition.POOR ? Theme.DANGER
                            : network == HealthReport.Condition.FAIR ? Theme.WARNING : Theme.TEXT_SECONDARY, 0.8f));
        }
        if (!detailed) {
            return rows;
        }
        if (health.live()) {
            rows.add(new Text(health.encoderName() + (health.isEncoderOverloaded()
                    ? " · overloaded " + health.queuePressurePercent() : ""), health.isEncoderOverloaded()
                    ? Theme.DANGER : Theme.TEXT_MUTED, 0.7f));
            for (StreamHealth.DestinationStatus d : health.destinations()) {
                rows.add(new Text("● " + d.name() + "  " + d.state().displayName(), d.state().colour() | 0xFF000000, 0.7f));
            }
        }
        if (client.config().recording.captureMicrophone) {
            LevelMeter.Reading reading = client.microphone().isCapturing()
                    ? client.microphone().processor().chain().outputLevel() : LevelMeter.Reading.SILENT;
            rows.add(new MicMeter(reading, client.config().microphone.muted));
        }
        return rows;
    }
}
