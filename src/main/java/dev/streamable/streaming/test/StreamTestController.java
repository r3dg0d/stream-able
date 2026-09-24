package dev.streamable.streaming.test;

import dev.streamable.ffmpeg.EncodeProfile;
import dev.streamable.streaming.StreamDestination;

/**
 * Runs at most one destination test at a time, and never during a live
 * broadcast (the test would compete with the stream for the encoder and
 * uplink it is trying to measure).
 */
public final class StreamTestController {

    private volatile StreamTestSession session;

    public StreamTestSession session() {
        return session;
    }

    /**
     * @return {@code null} when started, otherwise why not
     */
    public synchronized String start(StreamDestination destination, EncodeProfile profile, String ffmpeg,
                                     int durationSeconds, boolean live) {
        if (live) {
            return "Stop the stream before running a destination test.";
        }
        if (ffmpeg == null || ffmpeg.isEmpty()) {
            return "FFmpeg is not ready yet.";
        }
        StreamTestSession current = session;
        if (current != null && current.isRunning()) {
            return "A test is already running.";
        }
        StreamTestSession next = new StreamTestSession(destination, profile, ffmpeg, durationSeconds, new NetworkProbe(8000));
        session = next;
        next.start();
        return null;
    }

    public void cancel() {
        StreamTestSession current = session;
        if (current != null) {
            current.cancel();
        }
    }

    public boolean isRunning() {
        StreamTestSession current = session;
        return current != null && current.isRunning();
    }
}
