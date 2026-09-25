package dev.streamable.recording.replay;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayBufferPartsTest {

    private static final int FRAME = 4;   // stereo 16-bit

    @Test
    void parsesTheSegmentListAndKeepsTheLatestEntryOfAReusedFile() {
        List<ReplaySegments.Segment> s = ReplaySegments.parse(List.of(
                "seg000.ts,0.000000,4.066667",
                "seg001.ts,4.066667,6.066667",
                "seg000.ts,10.066667,12.066667",   // wrapped: seg000 reused
                "seg002.ts,6.066667,8.0666",
                "seg003.ts,8.06",                   // being written
                ""));
        assertEquals(3, s.size());
        assertEquals("seg001.ts", s.get(0).file());
        assertEquals("seg000.ts", s.getLast().file());
        assertEquals(10.066667, s.getLast().start(), 1e-9);
    }

    @Test
    void picksTheNewestContiguousRunCoveringTheRequestedLength() {
        List<ReplaySegments.Segment> all = List.of(
                new ReplaySegments.Segment("a", 0, 2), new ReplaySegments.Segment("b", 2, 4),
                new ReplaySegments.Segment("c", 4, 6), new ReplaySegments.Segment("d", 6, 8));
        List<ReplaySegments.Segment> run = ReplaySegments.lastSeconds(all, 5);
        assertEquals(List.of("b", "c", "d"), run.stream().map(ReplaySegments.Segment::file).toList());
        assertEquals(4, ReplaySegments.lastSeconds(all, 60).size(), "everything when asking for more");
    }

    @Test
    void neverJoinsAcrossAGap() {
        List<ReplaySegments.Segment> all = List.of(
                new ReplaySegments.Segment("old", 0, 2), new ReplaySegments.Segment("new1", 30, 32),
                new ReplaySegments.Segment("new2", 32, 34));
        assertEquals(List.of("new1", "new2"),
                ReplaySegments.lastSeconds(all, 10).stream().map(ReplaySegments.Segment::file).toList());
    }

    @Test
    void concatListQuotesPaths() {
        String list = ReplaySegments.concatList(List.of("/tmp/it's here/part000.ts"));
        assertTrue(list.startsWith("ffconcat version 1.0\n"));
        assertTrue(list.contains("file '/tmp/it'\\''s here/part000.ts'"), list);
    }

    @Test
    void audioIsCutByWallClockTimeSampleExact() {
        ReplayAudioRing ring = new ReplayAudioRing(10);
        long t = 1_000_000_000L;
        // 20 ms blocks whose left sample holds the block number; the first block covers 0.98..1.00 s.
        for (int b = 0; b < 100; b++) {
            t += 20_000_000L;
            ring.append(block(960, b), t);
        }
        // The first block's first sample is at 1.000 s; block 50 starts 1.000 s later.
        byte[] cut = ring.extract(2_000_000_000L, 960);
        assertEquals(50, sample(cut, 0));
        assertEquals(50, sample(cut, 959));
    }

    @Test
    void outsideTheHeldRangeIsSilenceAndLengthIsExact() {
        ReplayAudioRing ring = new ReplayAudioRing(1);   // holds 1 s
        long t = 0;
        for (int b = 1; b <= 100; b++) {                 // 2 s appended; the first second is gone
            t += 20_000_000L;
            ring.append(block(960, b), t);
        }
        byte[] early = ring.extract(0, 480);
        assertEquals(480 * FRAME, early.length);
        assertEquals(0, sample(early, 0), "overwritten audio comes back as silence");
        byte[] late = ring.extract(5_000_000_000L, 480);
        assertEquals(0, sample(late, 100));
        assertEquals(1.0, ring.heldSeconds(), 1e-9);
    }

    private static byte[] block(int frames, int value) {
        byte[] pcm = new byte[frames * FRAME];
        for (int f = 0; f < frames; f++) {
            pcm[f * FRAME] = (byte) (value & 0xFF);
            pcm[f * FRAME + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return pcm;
    }

    private static int sample(byte[] pcm, int frame) {
        return (short) ((pcm[frame * FRAME] & 0xFF) | (pcm[frame * FRAME + 1] << 8));
    }
}
