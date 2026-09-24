package dev.streamable.ui.kit;

import java.util.function.Consumer;

/** Layout containers: a vertical stack, a horizontal row, and a free-form node. */
public final class Layouts {

    private Layouts() {
    }

    /** Stacks children vertically at full width, each at its preferred height. */
    public static class Column extends UiNode {
        private final int gap;
        private final int padding;

        public Column(int gap, int padding) {
            this.gap = gap;
            this.padding = padding;
        }

        public Column(int gap) {
            this(gap, 0);
        }

        @Override
        protected void layout() {
            int cy = y + padding;
            int innerWidth = width - 2 * padding;
            for (UiNode child : children) {
                if (!child.isVisible()) {
                    continue;
                }
                int h = child.preferredHeight(innerWidth);
                child.setBounds(x + padding, cy, innerWidth, h);
                cy += h + gap;
            }
        }

        @Override
        public int preferredHeight(int availableWidth) {
            int total = 2 * padding;
            int count = 0;
            for (UiNode child : children) {
                if (child.isVisible()) {
                    total += child.preferredHeight(availableWidth - 2 * padding);
                    count++;
                }
            }
            return total + Math.max(0, count - 1) * gap;
        }
    }

    /**
     * Lays children out left to right. Each child has a width: positive values
     * are fixed widths, zero or negative values are weights sharing the rest.
     */
    public static class Row extends UiNode {
        private final int gap;
        private final java.util.List<Integer> widths = new java.util.ArrayList<>();

        public Row(int gap) {
            this.gap = gap;
        }

        /** Adds a child with a fixed width (px) or a weight (negative: -1 = one share). */
        public <T extends UiNode> T add(T child, int widthOrWeight) {
            widths.add(widthOrWeight);
            return super.add(child);
        }

        @Override
        public <T extends UiNode> T add(T child) {
            return add(child, -1);
        }

        @Override
        protected void layout() {
            int fixed = 0;
            int shares = 0;
            int visibleCount = 0;
            for (int i = 0; i < children.size(); i++) {
                if (!children.get(i).isVisible()) {
                    continue;
                }
                visibleCount++;
                int w = widths.get(i);
                if (w > 0) {
                    fixed += w;
                } else {
                    shares += Math.max(1, -w);
                }
            }
            int free = Math.max(0, width - fixed - gap * Math.max(0, visibleCount - 1));
            int cx = x;
            for (int i = 0; i < children.size(); i++) {
                UiNode child = children.get(i);
                if (!child.isVisible()) {
                    continue;
                }
                int w = widths.get(i);
                int actual = w > 0 ? w : (shares == 0 ? 0 : free * Math.max(1, -w) / shares);
                child.setBounds(cx, y, actual, height);
                cx += actual + gap;
            }
        }

        @Override
        public int preferredHeight(int availableWidth) {
            int h = 0;
            for (UiNode child : children) {
                if (child.isVisible()) {
                    h = Math.max(h, child.preferredHeight(Math.max(40, availableWidth / Math.max(1, children.size()))));
                }
            }
            return Math.max(h, Theme.CONTROL_HEIGHT);
        }
    }

    /** A node whose drawing and height are supplied by lambdas: graphs, previews, spacers. */
    public static class Custom extends UiNode {
        private final Consumer<Custom> painter;
        private Painter current;
        private final int fixedHeight;

        public Custom(int height, Consumer<Custom> painter) {
            this.fixedHeight = height;
            this.painter = painter;
        }

        public Painter painter() {
            return current;
        }

        @Override
        public int preferredHeight(int availableWidth) {
            return fixedHeight;
        }

        @Override
        protected void renderSelf(Painter p) {
            current = p;
            painter.accept(this);
        }
    }

    public static Custom spacer(int height) {
        return new Custom(height, n -> { });
    }
}
