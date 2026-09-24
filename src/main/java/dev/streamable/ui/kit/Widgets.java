package dev.streamable.ui.kit;

import dev.streamable.audio.dsp.LevelMeter;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Display components: section headers, status pills, meters, progress, metric cards, dividers. */
public final class Widgets {

    private Widgets() {
    }

    /** Title with an optional subtitle. */
    public static final class SectionHeader extends UiNode {
        private final String title;
        private final Supplier<String> subtitle;

        public SectionHeader(String title, Supplier<String> subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }

        public SectionHeader(String title) {
            this(title, () -> null);
        }

        @Override
        public int preferredHeight(int availableWidth) {
            return subtitle.get() == null ? 14 : 24;
        }

        @Override
        protected void renderSelf(Painter p) {
            p.text(title, x, y + 1, Theme.TEXT, 1.1f, Painter.Weight.SEMIBOLD);
            String sub = subtitle.get();
            if (sub != null) {
                p.textClipped(sub, x, y + 14, width, Theme.TEXT_MUTED, Theme.TEXT_CAPTION, Painter.Weight.REGULAR);
            }
        }
    }

    /** A small uppercase caption above a group. */
    public static final class Caption extends UiNode {
        private final String text;

        public Caption(String text) {
            this.text = text;
        }

        @Override
        public int preferredHeight(int availableWidth) {
            return 10;
        }

        @Override
        protected void renderSelf(Painter p) {
            p.text(text.toUpperCase(java.util.Locale.ROOT), x, y + 1, Theme.TEXT_MUTED, Theme.TEXT_CAPTION, Painter.Weight.SEMIBOLD);
        }
    }

    /** Coloured status pill with a dot, e.g. LIVE, Ready, Downloading 46 %. */
    public static final class StatusPill extends UiNode {
        private final Supplier<String> text;
        private final IntSupplier color;
        private final boolean pulse;
        private boolean alignRight;

        public StatusPill(Supplier<String> text, IntSupplier color, boolean pulse) {
            this.text = text;
            this.color = color;
            this.pulse = pulse;
        }

        /** Draws the pill against the right edge of its slot instead of the left. */
        public StatusPill alignRight() {
            this.alignRight = true;
            return this;
        }

        public int naturalWidth(Painter p) {
            return p.textWidth(text.get(), Theme.TEXT_CAPTION, Painter.Weight.SEMIBOLD) + 18;
        }

        @Override
        public int preferredHeight(int availableWidth) {
            return 13;
        }

        @Override
        protected void renderSelf(Painter p) {
            int px = alignRight ? x + width - naturalWidth(p) : x;
            drawPill(p, px, y, text.get(), color.getAsInt(), pulse);
        }

        public static int drawPill(Painter p, int px, int py, String label, int c, boolean pulse) {
            int w = p.textWidth(label, Theme.TEXT_CAPTION, Painter.Weight.SEMIBOLD) + 18;
            p.roundRect(px, py, w, 13, 6.5f, Theme.withAlpha(c, 0.16f));
            float alpha = pulse ? 0.55f + 0.45f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 400.0)) : 1f;
            p.circle(px + 7, py + 6.5f, 2.5f, Theme.withAlpha(c, alpha));
            p.text(label, px + 12, py + 3, c, Theme.TEXT_CAPTION, Painter.Weight.SEMIBOLD);
            return w;
        }
    }

    /**
     * Calibrated level meter in dBFS: RMS bar, peak tick, peak-hold marker,
     * clip lamp and a -60..0 dB scale. Colour zones mark real levels
     * (green below -18, amber to -6, red above), not decoration.
     */
    public static final class Meter extends UiNode {
        public static final double FLOOR_DB = -60;
        private final String label;
        private final Supplier<LevelMeter.Reading> reading;
        private final boolean showScale;
        private double shownRms = FLOOR_DB;

        public Meter(String label, Supplier<LevelMeter.Reading> reading, boolean showScale) {
            this.label = label;
            this.reading = reading;
            this.showScale = showScale;
        }

        @Override
        public int preferredHeight(int availableWidth) {
            return (label == null ? 0 : 11) + 8 + (showScale ? 9 : 0);
        }

        static float position(double db) {
            return (float) Math.clamp((db - FLOOR_DB) / -FLOOR_DB, 0, 1);
        }

        @Override
        protected void renderSelf(Painter p) {
            LevelMeter.Reading r = reading.get();
            if (r == null) {
                r = LevelMeter.Reading.SILENT;
            }
            int top = y;
            if (label != null) {
                p.text(label, x, y, Theme.TEXT_SECONDARY, Theme.TEXT_CAPTION, Painter.Weight.SEMIBOLD);
                String value = r.peakDb() <= -119 ? "-∞ dB" : String.format(java.util.Locale.ROOT, "%.1f dB", r.rmsDb());
                int vw = p.textWidth(value, Theme.TEXT_CAPTION, Painter.Weight.REGULAR);
                p.text(value, x + width - 10 - vw, y, Theme.TEXT_MUTED, Theme.TEXT_CAPTION, Painter.Weight.REGULAR);
                top += 11;
            }
            int barWidth = width - 8;
            p.roundRect(x, top, barWidth, 6, 3, 0xFF1E2230);
            // Smooth the RMS bar a little for readability; peaks are shown raw.
            shownRms += (r.rmsDb() - shownRms) * Math.min(1, p.delta() * 18);
            float rms = position(shownRms);
            float green = position(-18);
            float amber = position(-6);
            float end = barWidth * rms;
            p.pushClip(x, top, Math.max(0, Math.round(end)), 6);
            p.roundRect(x, top, barWidth * green, 6, 3, Theme.SUCCESS);
            p.fill(Math.round(x + barWidth * green), top, Math.round(barWidth * (amber - green)), 6, Theme.WARNING);
            p.roundRect(x + barWidth * amber, top, barWidth * (1 - amber), 6, 3, Theme.DANGER);
            p.popClip();
            float peak = position(r.peakDb());
            p.fill(Math.round(x + barWidth * peak), top, 1, 6, 0xDDFFFFFF);
            float hold = position(r.peakHoldDb());
            if (r.peakHoldDb() > FLOOR_DB) {
                p.fill(Math.round(x + barWidth * hold) - 1, top - 1, 2, 8, r.peakHoldDb() > -6 ? Theme.DANGER : Theme.TEXT_SECONDARY);
            }
            p.circle(x + width - 3, top + 3, 2.5f, r.clipping() ? Theme.DANGER : 0xFF2C3242);
            if (showScale) {
                for (int db : new int[]{-60, -48, -36, -24, -18, -12, -6, 0}) {
                    float sx = x + barWidth * position(db);
                    p.fill(Math.round(sx), top + 7, 1, 2, Theme.TEXT_MUTED);
                    String t = Integer.toString(db);
                    int tw = p.textWidth(t, 0.65f, Painter.Weight.REGULAR);
                    p.text(t, Math.min(x + barWidth - tw, Math.max(x, sx - tw / 2f)), top + 10, Theme.TEXT_MUTED, 0.65f, Painter.Weight.REGULAR);
                }
            }
        }
    }

    public static final class ProgressBar extends UiNode {
        private final DoubleSupplier fraction;
        private final IntSupplier color;

        public ProgressBar(DoubleSupplier fraction, IntSupplier color) {
            this.fraction = fraction;
            this.color = color;
        }

        @Override
        public int preferredHeight(int availableWidth) {
            return 4;
        }

        @Override
        protected void renderSelf(Painter p) {
            p.roundRect(x, y, width, 4, 2, 0xFF1E2230);
            double f = fraction.getAsDouble();
            if (f < 0) {
                // Indeterminate: a sliding segment.
                double t = (System.currentTimeMillis() % 1200) / 1200.0;
                float segment = width * 0.3f;
                float start = (float) (t * (width + segment)) - segment;
                p.pushClip(x, y, width, 4);
                p.roundRect(x + start, y, segment, 4, 2, color.getAsInt());
                p.popClip();
            } else {
                p.roundRect(x, y, (float) (width * Math.clamp(f, 0, 1)), 4, 2, color.getAsInt());
            }
        }
    }

    /** A compact metric: caption, big value, optional note. */
    public static final class MetricCard extends UiNode {
        private final String caption;
        private final Supplier<String> value;
        private final IntSupplier color;
        private final Supplier<String> note;

        public MetricCard(String caption, Supplier<String> value, IntSupplier color, Supplier<String> note) {
            this.caption = caption;
            this.value = value;
            this.color = color;
            this.note = note;
        }

        @Override
        public int preferredHeight(int availableWidth) {
            return 38;
        }

        @Override
        protected void renderSelf(Painter p) {
            p.roundRect(x, y, width, height, Theme.RADIUS, Theme.SURFACE_RAISED);
            p.text(caption.toUpperCase(java.util.Locale.ROOT), x + 7, y + 6, Theme.TEXT_MUTED, Theme.TEXT_CAPTION, Painter.Weight.SEMIBOLD);
            p.textClipped(value.get(), x + 7, y + 16, width - 12, color.getAsInt(), Theme.TEXT_TITLE, Painter.Weight.SEMIBOLD);
            String n = note.get();
            if (n != null) {
                p.textClipped(n, x + 7, y + 29, width - 12, Theme.TEXT_MUTED, 0.7f, Painter.Weight.REGULAR);
            }
        }
    }

    public static final class Divider extends UiNode {
        @Override
        public int preferredHeight(int availableWidth) {
            return 5;
        }

        @Override
        protected void renderSelf(Painter p) {
            p.fill(x, y + 2, width, 1, Theme.DIVIDER);
        }
    }

    /** A card: padded, rounded surface around a column of children. */
    public static final class Card extends Layouts.Column {
        private final int background;

        public Card(int gap) {
            this(gap, Theme.SURFACE);
        }

        public Card(int gap, int background) {
            super(gap, Theme.PANEL_PADDING);
            this.background = background;
        }

        @Override
        protected void renderSelf(Painter p) {
            p.roundRect(x, y, width, height, Theme.RADIUS_LARGE, background);
            p.roundBorder(x, y, width, height, Theme.RADIUS_LARGE, 1f, Theme.DIVIDER);
        }
    }

    /** A callout with an icon for warnings and notes. */
    public static final class Notice extends UiNode {
        private final Supplier<String> text;
        private final IntSupplier color;

        public Notice(Supplier<String> text, IntSupplier color) {
            this.text = text;
            this.color = color;
        }

        @Override
        public boolean isVisible() {
            String value = text.get();
            return super.isVisible() && value != null && !value.isEmpty();
        }

        @Override
        public int preferredHeight(int availableWidth) {
            String value = text.get();
            UiScreen screen = screen();
            if (value == null || screen == null) {
                return 0;
            }
            return Math.max(18, screen.measureParagraph(value, availableWidth - 24, Theme.TEXT_CAPTION) + 10);
        }

        @Override
        protected void renderSelf(Painter p) {
            int c = color.getAsInt();
            p.roundRect(x, y, width, height, Theme.RADIUS, Theme.withAlpha(c, 0.12f));
            p.fill(x, y + 3, 2, height - 6, c);
            Icons.draw(p, c == Theme.DANGER || c == Theme.WARNING ? Icons.Icon.WARNING : Icons.Icon.INFO,
                    x + 7, y + 5, 8, c);
            p.paragraph(text.get(), x + 20, y + 5, width - 26, Theme.TEXT, Theme.TEXT_CAPTION);
        }
    }
}
