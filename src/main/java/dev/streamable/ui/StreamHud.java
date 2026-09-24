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
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The compact stream HUD: a small panel on the player's own screen showing
 * LIVE / REC state and, while live, bitrate, encoder FPS, dropped frames and
 * the network condition. Detailed mode adds the encoder, each destination and
 * a microphone meter.
 *
 * <p>It is a HUD element, not a source: it is drawn after the program frame is
 * captured and never reaches recordings or streams. It is movable - drag it in
 * the canvas editor, where it is always shown so it can be placed - and its
 * position is saved as a fraction of the screen, so it stays put across window
 * sizes, including ultrawide.</p>
 */
public final class StreamHud implements HudElement {

    private static final int PAD = 5;
    private static final int MARGIN = 6;

    /** Last drawn bounds in GUI pixels, for dragging in the canvas editor. */
    private static volatile int[] lastBounds = new int[4];

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        StreamAbleClient client = StreamAbleClient.get();
        Minecraft mc = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        boolean editing = mc.screen instanceof SourceEditorScreen;
        InterfaceSettings ui = client.config().ui;
        StreamHealth health = client.health();
        boolean recording = client.recording().isActive();
        if (!editing && (!ui.showStreamHud || (!health.live() && !recording))) {
            lastBounds = new int[4];
            return;
        }
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
        float fx = ui.streamHudX < 0 ? 1f : ui.streamHudX;
        float fy = ui.streamHudY < 0 ? 0f : ui.streamHudY;
        int x = MARGIN + Math.round(freeW * fx);
        int y = MARGIN + Math.round(freeH * fy);
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
        if (labels.isEmpty()) {
            labels.add(editing ? "HUD - drag to move" : "Idle");
            colors.add(Theme.TEXT_MUTED);
        }
        rows.add(new Pills(labels, colors));

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
