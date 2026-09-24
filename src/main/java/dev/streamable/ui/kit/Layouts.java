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
                // Children keep their own height, bottom-aligned, so a button
                // lines up with the box of a labelled field beside it.
                int h = Math.min(height, child.preferredHeight(actual));
                child.setBounds(cx, y + height - h, actual, h);
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

    /**
     * A row when there is room, a column when there is not. Children share the
     * width by weight in row mode; below {@code breakpoint} they stack at full width.
     */
    public static class Adaptive extends UiNode {
        private final int breakpoint;
        private final int gap;
        private final java.util.List<Integer> weights = new java.util.ArrayList<>();

        public Adaptive(int breakpoint, int gap) {
            this.breakpoint = breakpoint;
            this.gap = gap;
        }

        public <T extends UiNode> T add(T child, int weight) {
            weights.add(Math.max(1, weight));
            return super.add(child);
        }

        @Override
        public <T extends UiNode> T add(T child) {
            return add(child, 1);
        }

        private boolean horizontal(int w) {
            return w >= breakpoint;
        }

        private int[] widths(int total) {
            int shares = 0;
            int count = 0;
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i).isVisible()) {
                    shares += weights.get(i);
                    count++;
                }
            }
            int free = Math.max(0, total - gap * Math.max(0, count - 1));
            int[] out = new int[children.size()];
            for (int i = 0; i < children.size(); i++) {
                out[i] = shares == 0 ? 0 : free * weights.get(i) / shares;
            }
            return out;
        }

        @Override
        protected void layout() {
            if (horizontal(width)) {
                int[] w = widths(width);
                int cx = x;
                for (int i = 0; i < children.size(); i++) {
                    UiNode child = children.get(i);
                    if (!child.isVisible()) {
                        continue;
                    }
                    child.setBounds(cx, y, w[i], child.preferredHeight(w[i]));
                    cx += w[i] + gap;
                }
            } else {
                int cy = y;
                for (UiNode child : children) {
                    if (!child.isVisible()) {
                        continue;
                    }
                    int h = child.preferredHeight(width);
                    child.setBounds(x, cy, width, h);
                    cy += h + gap;
                }
            }
        }

        @Override
        public int preferredHeight(int availableWidth) {
            if (horizontal(availableWidth)) {
                int[] w = widths(availableWidth);
                int h = 0;
                for (int i = 0; i < children.size(); i++) {
                    if (children.get(i).isVisible()) {
                        h = Math.max(h, children.get(i).preferredHeight(w[i]));
                    }
                }
                return h;
            }
            int total = 0;
            int count = 0;
            for (UiNode child : children) {
                if (child.isVisible()) {
                    total += child.preferredHeight(availableWidth);
                    count++;
                }
            }
            return total + Math.max(0, count - 1) * gap;
        }
    }

    /** Equal-width cells that wrap onto new lines: metric cards, preset chips. */
    public static class Grid extends UiNode {
        private final int minCellWidth;
        private final int gap;

        public Grid(int minCellWidth, int gap) {
            this.minCellWidth = minCellWidth;
            this.gap = gap;
        }

        private int columns(int w) {
            return Math.max(1, (w + gap) / (minCellWidth + gap));
        }

        private java.util.List<UiNode> shown() {
            return children.stream().filter(UiNode::isVisible).toList();
        }

        @Override
        protected void layout() {
            java.util.List<UiNode> cells = shown();
            int cols = Math.min(columns(width), Math.max(1, cells.size()));
            int cellW = (width - gap * (cols - 1)) / cols;
            int cy = y;
            for (int start = 0; start < cells.size(); start += cols) {
                int rowH = 0;
                for (int i = start; i < Math.min(cells.size(), start + cols); i++) {
                    rowH = Math.max(rowH, cells.get(i).preferredHeight(cellW));
                }
                for (int i = start; i < Math.min(cells.size(), start + cols); i++) {
                    int h = cells.get(i).preferredHeight(cellW);
                    cells.get(i).setBounds(x + (i - start) * (cellW + gap), cy + rowH - h, cellW, h);
                }
                cy += rowH + gap;
            }
        }

        @Override
        public int preferredHeight(int availableWidth) {
            java.util.List<UiNode> cells = shown();
            if (cells.isEmpty()) {
                return 0;
            }
            int cols = Math.min(columns(availableWidth), cells.size());
            int cellW = (availableWidth - gap * (cols - 1)) / cols;
            int total = 0;
            for (int start = 0; start < cells.size(); start += cols) {
                int rowH = 0;
                for (int i = start; i < Math.min(cells.size(), start + cols); i++) {
                    rowH = Math.max(rowH, cells.get(i).preferredHeight(cellW));
                }
                total += rowH + gap;
            }
            return total - gap;
        }
    }
}
