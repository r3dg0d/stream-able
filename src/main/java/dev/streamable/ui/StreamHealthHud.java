package dev.streamable.ui;

import dev.streamable.StreamAbleClient;
import dev.streamable.config.InterfaceSettings;
import dev.streamable.recording.RecordingController;
import dev.streamable.streaming.StreamHealth;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The small in-game stream status overlay.
 *
 * <pre>
 *   ● LIVE  01:42:19
 *   12,180 kbps
 *   60 FPS
 *   Dropped: 18
 *   Twitch  ✓
 *   YouTube ✓
 * </pre>
 *
 * <p>This is Stream-able's own HUD, <em>not</em> a browser source: it is drawn
 * with Minecraft's font on the local screen only and never reaches the program
 * frame, so viewers do not see the streamer's diagnostics. Stream statistics
 * live here rather than in chat.</p>
 */
public final class StreamHealthHud implements HudElement {

    private static final int PADDING = 6;
    private static final int LINE_HEIGHT = 10;

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        StreamAbleClient runtime = StreamAbleClient.get();
        if (runtime == null) {
            return;
        }
        InterfaceSettings ui = runtime.config().ui;
        if (!ui.showStreamHud) {
            return;
        }
        StreamHealth health = runtime.health();
        boolean recording = runtime.recording().isActive();
        if (!health.live() && !recording) {
            return;   // nothing worth showing while idle
        }

        List<Line> lines = buildLines(runtime, health, recording, ui.detailedStreamHud);
        if (lines.isEmpty()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        int textWidth = 0;
        for (Line line : lines) {
            textWidth = Math.max(textWidth, client.font.width(line.text()));
        }
        int boxWidth = textWidth + PADDING * 2;
        int boxHeight = lines.size() * LINE_HEIGHT + PADDING * 2 - 2;

        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        int x = switch (ui.streamHudPosition) {
            case 1, 3 -> screenWidth - boxWidth - 8;
            default -> 8;
        };
        int y = switch (ui.streamHudPosition) {
            case 2, 3 -> screenHeight - boxHeight - 8;
            default -> 8;
        };

        int backgroundAlpha = (int) (ui.streamHudOpacity * 0xB0) << 24;
        graphics.fill(x, y, x + boxWidth, y + boxHeight, backgroundAlpha);

        int textY = y + PADDING;
        for (Line line : lines) {
            graphics.text(client.font, line.text(), x + PADDING, textY, line.colour());
            textY += LINE_HEIGHT;
        }
    }

    private record Line(String text, int colour) {
    }

    private static List<Line> buildLines(StreamAbleClient runtime, StreamHealth health,
                                         boolean recording, boolean detailed) {
        List<Line> lines = new ArrayList<>();
        if (health.live()) {
            // Only claim LIVE once a destination has actually confirmed it;
            // otherwise the HUD would show a red dot for a stream that never
            // connected.
            boolean connected = health.liveDestinationCount() > 0;
            lines.add(connected
                    ? new Line("● LIVE  " + health.formattedUptime(), 0xFFFF5252)
                    : new Line("● CONNECTING  " + health.formattedUptime(), 0xFFFFC107));
        }
        if (recording) {
            RecordingController controller = runtime.recording();
            lines.add(new Line("● REC   " + formatDuration(controller.elapsedMillis()), 0xFFFFB300));
        }
        if (!detailed) {
            return lines;
        }
        if (health.live()) {
            lines.add(new Line(String.format(Locale.ROOT, "%,d kbps", health.videoBitrateKbps()), 0xFFE0E0E0));
            lines.add(new Line(health.fps() + " FPS", 0xFFE0E0E0));
            lines.add(new Line("Dropped: " + health.framesDropped(),
                    health.framesDropped() > 0 ? 0xFFFFB74D : 0xFFE0E0E0));
            if (health.isEncoderOverloaded()) {
                lines.add(new Line("Encoder overloaded " + health.queuePressurePercent(), 0xFFEF5350));
            }
            for (StreamHealth.DestinationStatus destination : health.destinations()) {
                lines.add(new Line(destination.name() + "  " + destination.state().displayName(),
                        destination.state().colour()));
            }
        }
        return lines;
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }
}
