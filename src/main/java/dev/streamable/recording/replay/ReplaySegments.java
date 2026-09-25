package dev.streamable.recording.replay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The replay buffer's segment list (FFmpeg segment muxer, CSV format:
 * {@code filename,start,end} per finished segment, times in seconds on the
 * encoder timeline) and the choice of segments for a clip.
 */
public final class ReplaySegments {

    public record Segment(String file, double start, double end) {
        public double duration() {
            return end - start;
        }
    }

    private ReplaySegments() {
    }

    /**
     * Parses the CSV list. Malformed or partially written lines are skipped;
     * when the ring has wrapped, the same file name can appear more than once
     * and only its latest entry is current.
     */
    public static List<Segment> parse(List<String> lines) {
        List<Segment> segments = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            int second = line.lastIndexOf(',');
            int first = second <= 0 ? -1 : line.lastIndexOf(',', second - 1);
            if (first <= 0) {
                continue;
            }
            try {
                String file = line.substring(0, first);
                double start = Double.parseDouble(line.substring(first + 1, second));
                double end = Double.parseDouble(line.substring(second + 1));
                if (!file.isEmpty() && end > start && Double.isFinite(start) && Double.isFinite(end)) {
                    segments.removeIf(s -> s.file().equals(file));
                    segments.add(new Segment(file, start, end));
                }
            } catch (NumberFormatException e) {
                // A line being written as we read it.
            }
        }
        segments.sort(Comparator.comparingDouble(Segment::start));
        return segments;
    }

    /**
     * The newest contiguous run of segments covering at least the last
     * {@code seconds} (or everything available). Stops at a gap, so a clip never
     * joins footage that is not continuous.
     */
    public static List<Segment> lastSeconds(List<Segment> all, double seconds) {
        List<Segment> picked = new ArrayList<>();
        double covered = 0;
        for (int i = all.size() - 1; i >= 0 && covered < seconds; i--) {
            Segment s = all.get(i);
            if (!picked.isEmpty() && Math.abs(picked.getFirst().start() - s.end()) > 0.5) {
                break;
            }
            picked.addFirst(s);
            covered += s.duration();
        }
        return picked;
    }

    /** The concat demuxer's list for a set of copied segment files. */
    public static String concatList(List<String> absolutePaths) {
        StringBuilder text = new StringBuilder("ffconcat version 1.0\n");
        for (String path : absolutePaths) {
            // Single quotes are the concat format's quoting; escape any in the path.
            text.append("file '").append(path.replace("'", "'\\''")).append("'\n");
        }
        return text.toString();
    }

    static String describe(List<Segment> segments) {
        if (segments.isEmpty()) {
            return "no segments";
        }
        return String.format(Locale.ROOT, "%d segments, %.1f s to %.1f s", segments.size(),
                segments.getFirst().start(), segments.getLast().end());
    }
}
