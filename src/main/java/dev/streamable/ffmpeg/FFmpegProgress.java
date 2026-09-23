package dev.streamable.ffmpeg;

import java.util.Locale;

/**
 * One block of FFmpeg's machine-readable {@code -progress} output.
 *
 * <p>These are FFmpeg's own measurements of what it actually did, which is what
 * Stream Health and the destination tester report - not configured values.</p>
 *
 * @param frame         frames encoded so far
 * @param fps           FFmpeg's encode rate
 * @param bitrateKbps   average output bitrate so far, or {@code -1} when unknown
 * @param totalSize     bytes written to the output(s) so far, or {@code -1}
 * @param outTimeMicros media time encoded so far
 * @param duplicated    frames FFmpeg duplicated to hold constant frame rate
 * @param dropped       frames FFmpeg dropped
 * @param speed         encode speed relative to real time (1.0 = keeping up), or {@code -1}
 * @param ended         whether this is the final block
 */
public record FFmpegProgress(long frame, double fps, double bitrateKbps, long totalSize, long outTimeMicros,
                             long duplicated, long dropped, double speed, boolean ended) {

    public static final FFmpegProgress NONE = new FFmpegProgress(0, 0, -1, -1, 0, 0, 0, -1, false);

    /** Accumulates {@code key=value} lines until a {@code progress=} line completes a block. */
    public static final class Parser {
        private long frame;
        private double fps;
        private double bitrate = -1;
        private long totalSize = -1;
        private long outTime;
        private long dup;
        private long drop;
        private double speed = -1;

        /** Feeds one line; returns a completed block, or {@code null}. */
        public FFmpegProgress accept(String line) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                return null;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            switch (key) {
                case "frame" -> frame = parseLong(value, frame);
                case "fps" -> fps = parseDouble(value, fps);
                case "bitrate" -> bitrate = parseBitrate(value);
                case "total_size" -> totalSize = parseLong(value, totalSize);
                case "out_time_us", "out_time_ms" -> outTime = parseLong(value, outTime);
                case "dup_frames" -> dup = parseLong(value, dup);
                case "drop_frames" -> drop = parseLong(value, drop);
                case "speed" -> speed = parseSpeed(value);
                case "progress" -> {
                    return new FFmpegProgress(frame, fps, bitrate, totalSize, outTime, dup, drop, speed,
                            value.equalsIgnoreCase("end"));
                }
                default -> {
                }
            }
            return null;
        }

        static double parseBitrate(String value) {
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("n/a")) {
                return -1;
            }
            try {
                if (lower.endsWith("kbits/s")) {
                    return Double.parseDouble(lower.substring(0, lower.length() - 7).trim());
                }
                if (lower.endsWith("mbits/s")) {
                    return Double.parseDouble(lower.substring(0, lower.length() - 7).trim()) * 1000.0;
                }
                if (lower.endsWith("bits/s")) {
                    return Double.parseDouble(lower.substring(0, lower.length() - 6).trim()) / 1000.0;
                }
                return Double.parseDouble(lower);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        static double parseSpeed(String value) {
            String trimmed = value.endsWith("x") ? value.substring(0, value.length() - 1) : value;
            return parseDouble(trimmed.trim(), -1);
        }

        private static long parseLong(String value, long fallback) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static double parseDouble(String value, double fallback) {
            try {
                double parsed = Double.parseDouble(value);
                return Double.isFinite(parsed) ? parsed : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
