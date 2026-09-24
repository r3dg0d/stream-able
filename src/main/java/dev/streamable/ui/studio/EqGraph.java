package dev.streamable.ui.studio;

import dev.streamable.audio.dsp.EqualizerStage;
import dev.streamable.audio.dsp.MicrophoneChain;
import dev.streamable.config.MicrophoneSettings;
import dev.streamable.ui.kit.Painter;
import dev.streamable.ui.kit.Theme;
import dev.streamable.ui.kit.UiNode;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

/**
 * The EQ's combined frequency response, computed from the same biquad
 * designs the DSP uses, on a log-frequency axis.
 *
 * <p>Each band has a handle: drag it to change frequency and gain. Once the
 * graph has focus (click it), scrolling over a handle changes Q (bandwidth),
 * [ and ] select a band, and the arrow keys move it.</p>
 */
final class EqGraph extends UiNode {

    private static final double MIN_HZ = 20;
    private static final double MAX_HZ = 20_000;
    private static final double RANGE_DB = 18;

    private final Studio studio;
    private int selected = -1;
    private boolean dragging;

    EqGraph(Studio studio) {
        this.studio = studio;
        tooltip("Drag a handle to change frequency and gain. Click the graph, then scroll over a handle to change bandwidth (Q).");
    }

    private MicrophoneSettings.Equalizer eq() {
        return studio.client().config().microphone.eq;
    }

    @Override
    public int preferredHeight(int availableWidth) {
        return 96;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    private float xOf(double hz) {
        double t = Math.log(hz / MIN_HZ) / Math.log(MAX_HZ / MIN_HZ);
        return (float) (x + 4 + t * (width - 8));
    }

    private double hzAt(double px) {
        double t = Math.clamp((px - x - 4) / (width - 8), 0, 1);
        return MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, t);
    }

    private float yOf(double db) {
        double t = (RANGE_DB - Math.clamp(db, -RANGE_DB, RANGE_DB)) / (2 * RANGE_DB);
        return (float) (y + 4 + t * (height - 8));
    }

    private double dbAt(double py) {
        double t = Math.clamp((py - y - 4) / (height - 8), 0, 1);
        return RANGE_DB - t * 2 * RANGE_DB;
    }

    @Override
    protected void renderSelf(Painter p) {
        p.roundRect(x, y, width, height, Theme.RADIUS, Theme.FIELD);
        for (double hz : new double[]{50, 100, 200, 500, 1000, 2000, 5000, 10000}) {
            float gx = xOf(hz);
            p.fill(Math.round(gx), y + 2, 1, height - 4, Theme.DIVIDER);
            String label = hz >= 1000 ? (int) (hz / 1000) + "k" : Integer.toString((int) hz);
            p.text(label, gx + 2, y + height - 9, Theme.TEXT_MUTED, 0.65f, Painter.Weight.REGULAR);
        }
        for (int db : new int[]{-12, -6, 0, 6, 12}) {
            float gy = yOf(db);
            p.fill(x + 2, Math.round(gy), width - 4, 1, db == 0 ? Theme.BORDER : Theme.DIVIDER);
            p.text((db > 0 ? "+" : "") + db, x + 3, gy - 7, Theme.TEXT_MUTED, 0.65f, Painter.Weight.REGULAR);
        }

        MicrophoneSettings.Equalizer eq = eq();
        int color = eq.enabled ? Theme.ACCENT : Theme.TEXT_DISABLED;
        int steps = Math.max(32, (width - 8) / 2);
        float prevX = 0;
        float prevY = 0;
        for (int i = 0; i <= steps; i++) {
            double hz = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, i / (double) steps);
            float px = xOf(hz);
            float py = yOf(EqualizerStage.responseDb(eq, hz, MicrophoneChain.SAMPLE_RATE));
            if (i > 0) {
                p.line(prevX, prevY, px, py, 1.5f, color);
            }
            prevX = px;
            prevY = py;
        }

        List<MicrophoneSettings.EqBand> bands = eq.bands;
        for (int i = 0; i < bands.size(); i++) {
            MicrophoneSettings.EqBand band = bands.get(i);
            float hx = xOf(band.frequencyHz);
            float hy = yOf(band.type == MicrophoneSettings.BandType.LOW_PASS ? 0 : band.gainDb);
            boolean active = i == selected;
            int handle = !band.enabled ? Theme.TEXT_DISABLED : active ? Theme.TEXT : Theme.ACCENT_HOVER;
            p.circle(hx, hy, active ? 4.5f : 3.5f, handle);
            p.text(Integer.toString(i + 1), hx - 2, hy - 13, Theme.TEXT_MUTED, 0.65f, Painter.Weight.SEMIBOLD);
        }
        if (selected >= 0 && selected < bands.size()) {
            MicrophoneSettings.EqBand band = bands.get(selected);
            String info = String.format(Locale.ROOT, "Band %d · %s · %.0f Hz · %+.1f dB · Q %.2f", selected + 1,
                    band.type.name().toLowerCase(Locale.ROOT).replace('_', ' '), band.frequencyHz, band.gainDb, band.q);
            int w = p.textWidth(info, Theme.TEXT_CAPTION, Painter.Weight.REGULAR);
            p.text(info, x + width - w - 6, y + 5, Theme.TEXT_SECONDARY, Theme.TEXT_CAPTION, Painter.Weight.REGULAR);
        }
    }

    private int bandNear(double mx, double my) {
        List<MicrophoneSettings.EqBand> bands = eq().bands;
        int best = -1;
        double bestDistance = 8;
        for (int i = 0; i < bands.size(); i++) {
            MicrophoneSettings.EqBand band = bands.get(i);
            double d = Math.hypot(mx - xOf(band.frequencyHz), my - yOf(band.gainDb));
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    @Override
    public boolean mouseDown(double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        int near = bandNear(mx, my);
        if (near >= 0) {
            selected = near;
            dragging = true;
            pressed = true;
        }
        return true;
    }

    @Override
    public void mouseDrag(double mx, double my) {
        if (!dragging || selected < 0 || selected >= eq().bands.size()) {
            return;
        }
        MicrophoneSettings.EqBand band = eq().bands.get(selected);
        band.frequencyHz = Math.round(hzAt(mx));
        if (band.type != MicrophoneSettings.BandType.LOW_PASS) {
            band.gainDb = Math.round(dbAt(my) * 2) / 2.0;
        }
        studio.micChanged();
    }

    @Override
    public void mouseUp(double mx, double my, int button) {
        dragging = false;
        pressed = false;
    }

    @Override
    public boolean mouseScroll(double mx, double my, double amount) {
        if (!isFocused()) {
            return false;   // click the graph first; plain scrolling moves the page
        }
        int near = bandNear(mx, my);
        if (near < 0) {
            return false;
        }
        selected = near;
        MicrophoneSettings.EqBand band = eq().bands.get(near);
        band.q = Math.clamp(band.q * (amount > 0 ? 1.15 : 1 / 1.15), 0.1, 12);
        studio.micChanged();
        return true;
    }

    @Override
    public boolean keyDown(int key, int modifiers) {
        List<MicrophoneSettings.EqBand> bands = eq().bands;
        if (bands.isEmpty()) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_RIGHT_BRACKET || key == GLFW.GLFW_KEY_LEFT_BRACKET) {
            selected = Math.floorMod(selected + (key == GLFW.GLFW_KEY_RIGHT_BRACKET ? 1 : -1), bands.size());
            return true;
        }
        if (selected < 0 || selected >= bands.size()) {
            return false;
        }
        MicrophoneSettings.EqBand band = bands.get(selected);
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> band.frequencyHz = Math.max(20, band.frequencyHz / 1.06);
            case GLFW.GLFW_KEY_RIGHT -> band.frequencyHz = Math.min(20_000, band.frequencyHz * 1.06);
            case GLFW.GLFW_KEY_UP -> band.gainDb = Math.min(18, band.gainDb + 0.5);
            case GLFW.GLFW_KEY_DOWN -> band.gainDb = Math.max(-18, band.gainDb - 0.5);
            default -> {
                return false;
            }
        }
        studio.micChanged();
        return true;
    }

    int selectedBand() {
        return selected;
    }
}
