package dev.streamable.source;

import dev.streamable.source.transform.Point2;
import dev.streamable.source.transform.SourceTransform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceListTest {

    private SourceList list;
    private BrowserSource bottom;
    private BrowserSource middle;
    private BrowserSource top;

    @BeforeEach
    void setUp() {
        list = new SourceList();
        bottom = BrowserSource.create("Webcam", "about:blank", 0, 0, 100, 100);
        middle = BrowserSource.create("Chat", "about:blank", 0, 0, 100, 100);
        top = BrowserSource.create("Alerts", "about:blank", 0, 0, 100, 100);
        list.addAll(List.of(bottom, middle, top));
    }

    private List<String> names() {
        return list.snapshot().stream().map(BrowserSource::name).toList();
    }

    @Test
    void insertOrderIsBackToFront() {
        assertEquals(List.of("Webcam", "Chat", "Alerts"), names());
    }

    @Test
    void displayOrderShowsTopmostFirst() {
        assertEquals(List.of("Alerts", "Chat", "Webcam"),
                list.displayOrder().stream().map(BrowserSource::name).toList());
    }

    @Test
    void moveUpRaisesOneStep() {
        assertTrue(list.moveUp(bottom.id()));
        assertEquals(List.of("Chat", "Webcam", "Alerts"), names());
    }

    @Test
    void moveDownLowersOneStep() {
        assertTrue(list.moveDown(top.id()));
        assertEquals(List.of("Webcam", "Alerts", "Chat"), names());
    }

    @Test
    void movingBeyondTheEndsIsANoOp() {
        assertFalse(list.moveDown(bottom.id()));
        assertFalse(list.moveUp(top.id()));
        assertEquals(List.of("Webcam", "Chat", "Alerts"), names());
    }

    @Test
    void moveToTopAndBottom() {
        assertTrue(list.moveToTop(bottom.id()));
        assertEquals(List.of("Chat", "Alerts", "Webcam"), names());
        assertTrue(list.moveToBottom(bottom.id()));
        assertEquals(List.of("Webcam", "Chat", "Alerts"), names());
    }

    @Test
    void removeById() {
        assertTrue(list.remove(middle.id()));
        assertEquals(List.of("Webcam", "Alerts"), names());
        assertFalse(list.remove(middle.id()));
    }

    @Test
    void lookupByIdSurvivesRenaming() {
        middle.setName("Twitch Chat");
        assertTrue(list.byId(middle.id()).isPresent());
        assertEquals("Twitch Chat", list.byId(middle.id()).orElseThrow().name());
    }

    @Test
    void snapshotIsImmutableAndStable() {
        List<BrowserSource> snapshot = list.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(top));
        list.remove(top.id());
        assertEquals(3, snapshot.size(), "an existing snapshot must not change under the reader");
    }

    @Test
    void pickTopmostPrefersTheFrontLayer() {
        bottom.setTransform(SourceTransform.of(0, 0, 200, 200));
        middle.setTransform(SourceTransform.of(0, 0, 200, 200));
        top.setTransform(SourceTransform.of(0, 0, 200, 200));
        assertEquals(top, list.pickTopmostAt(new Point2(100, 100)));
    }

    @Test
    void pickSkipsHiddenAndLockedSources() {
        bottom.setTransform(SourceTransform.of(0, 0, 200, 200));
        middle.setTransform(SourceTransform.of(0, 0, 200, 200));
        top.setTransform(SourceTransform.of(0, 0, 200, 200));
        top.setVisible(false);
        middle.setLocked(true);
        assertEquals(bottom, list.pickTopmostAt(new Point2(100, 100)));
    }

    @Test
    void pickReturnsNullOutsideEverySource() {
        bottom.setTransform(SourceTransform.of(0, 0, 100, 100));
        middle.setTransform(SourceTransform.of(0, 0, 100, 100));
        top.setTransform(SourceTransform.of(0, 0, 100, 100));
        assertNull(list.pickTopmostAt(new Point2(500, 500)));
    }

    @Test
    void duplicateGetsANewIdentityAndOffset() {
        BrowserSource copy = top.duplicate();
        assertNotEquals(top.id(), copy.id());
        assertEquals(top.transform().x() + 24, copy.transform().x());
        assertTrue(copy.name().contains("copy"));
    }
}
