package dev.streamable.runtime;

import java.util.Locale;

/**
 * Immutable snapshot of a runtime's state, safe to read from the render thread.
 *
 * @param state      lifecycle state
 * @param fraction   progress of the current step in {@code [0,1]}, or {@code -1} when unknown
 * @param bytesDone  bytes transferred so far in the current download
 * @param bytesTotal expected total bytes, or {@code -1} when unknown
 * @param detail     short human-readable explanation (never contains credentials)
 */
public record RuntimeProgress(RuntimeState state, double fraction, long bytesDone, long bytesTotal, String detail) {

    public RuntimeProgress {
        state = state == null ? RuntimeState.NOT_INSTALLED : state;
        fraction = Double.isFinite(fraction) ? Math.clamp(fraction, -1.0, 1.0) : -1.0;
        detail = detail == null ? "" : detail;
    }

    public static RuntimeProgress of(RuntimeState state, String detail) {
        return new RuntimeProgress(state, -1, 0, -1, detail);
    }

    public static RuntimeProgress downloading(long done, long total, String detail) {
        double fraction = total > 0 ? done / (double) total : -1;
        return new RuntimeProgress(RuntimeState.DOWNLOADING, fraction, done, total, detail);
    }

    /** "46%" style text, or an empty string when progress is unknown. */
    public String percentText() {
        return fraction < 0 ? "" : Math.round(fraction * 100) + "%";
    }

    /** One line for the UI, e.g. {@code "Downloading - 46% (67.1 / 145.2 MB)"}. */
    public String summary() {
        StringBuilder text = new StringBuilder(state.displayName());
        if (state == RuntimeState.DOWNLOADING && fraction >= 0) {
            text.append(" - ").append(percentText());
            if (bytesTotal > 0) {
                text.append(String.format(Locale.ROOT, " (%.1f / %.1f MB)",
                        bytesDone / 1_048_576.0, bytesTotal / 1_048_576.0));
            }
        } else if (state.isBusy() && fraction >= 0) {
            text.append(" - ").append(percentText());
        }
        return text.toString();
    }
}
