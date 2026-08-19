package dev.streamable.source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Ordered collection of sources with deterministic z-ordering.
 *
 * <p>The list is stored in <em>render order</em>: index {@code 0} is drawn
 * first and therefore appears at the back. The Sources panel shows the reverse,
 * because users expect the topmost layer at the top of the list - that is what
 * {@link #displayOrder()} is for.</p>
 *
 * <p>The same ordering feeds the local overlay, the recording and the stream,
 * so a source can never appear above another locally but below it on stream.</p>
 *
 * <p><b>Threading:</b> mutations happen on the client thread while the
 * compositor reads on the render thread. Rather than locking the renderer,
 * every mutation publishes a fresh immutable snapshot to a volatile field;
 * readers call {@link #snapshot()} and get a stable list for the whole frame.</p>
 */
public final class SourceList {

    private final List<BrowserSource> sources = new ArrayList<>();
    private volatile List<BrowserSource> snapshot = List.of();

    /** Immutable back-to-front list, safe to iterate from any thread. */
    public List<BrowserSource> snapshot() {
        return snapshot;
    }

    /** Immutable front-to-back list, matching what the Sources panel shows. */
    public List<BrowserSource> displayOrder() {
        List<BrowserSource> copy = new ArrayList<>(snapshot);
        Collections.reverse(copy);
        return List.copyOf(copy);
    }

    public synchronized int size() {
        return sources.size();
    }

    public synchronized boolean isEmpty() {
        return sources.isEmpty();
    }

    public synchronized void add(BrowserSource source) {
        Objects.requireNonNull(source, "source");
        sources.add(source);
        publish();
    }

    public synchronized void addAll(List<BrowserSource> toAdd) {
        for (BrowserSource source : toAdd) {
            if (source != null) {
                sources.add(source);
            }
        }
        publish();
    }

    public synchronized boolean remove(UUID id) {
        boolean removed = sources.removeIf(s -> s.id().equals(id));
        if (removed) {
            publish();
        }
        return removed;
    }

    public synchronized void clear() {
        sources.clear();
        publish();
    }

    public Optional<BrowserSource> byId(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        for (BrowserSource source : snapshot) {
            if (source.id().equals(id)) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }

    public synchronized int indexOf(UUID id) {
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** Raises the source one step towards the front. */
    public synchronized boolean moveUp(UUID id) {
        return swap(indexOf(id), 1);
    }

    /** Lowers the source one step towards the back. */
    public synchronized boolean moveDown(UUID id) {
        return swap(indexOf(id), -1);
    }

    public synchronized boolean moveToTop(UUID id) {
        return moveTo(id, sources.size() - 1);
    }

    public synchronized boolean moveToBottom(UUID id) {
        return moveTo(id, 0);
    }

    private boolean swap(int index, int delta) {
        if (index < 0) {
            return false;
        }
        int target = index + delta;
        if (target < 0 || target >= sources.size()) {
            return false;
        }
        Collections.swap(sources, index, target);
        publish();
        return true;
    }

    private boolean moveTo(UUID id, int target) {
        int index = indexOf(id);
        if (index < 0 || index == target) {
            return false;
        }
        BrowserSource source = sources.remove(index);
        sources.add(Math.clamp(target, 0, sources.size()), source);
        publish();
        return true;
    }

    /**
     * Returns the topmost source whose rotated bounds contain the canvas point
     * and which can be selected, or {@code null}.
     *
     * <p>Iterates front-to-back so the visually topmost source wins, and skips
     * hidden and locked sources so they cannot be grabbed by accident.</p>
     */
    public BrowserSource pickTopmostAt(dev.streamable.source.transform.Point2 canvasPoint) {
        List<BrowserSource> current = snapshot;
        for (int i = current.size() - 1; i >= 0; i--) {
            BrowserSource source = current.get(i);
            if (source.visible() && !source.locked() && source.transform().contains(canvasPoint)) {
                return source;
            }
        }
        return null;
    }

    private void publish() {
        snapshot = List.copyOf(sources);
    }
}
