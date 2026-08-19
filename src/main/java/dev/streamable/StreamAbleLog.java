package dev.streamable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Named loggers, one per subsystem.
 *
 * <p>Splitting them lets a user raise the level of just the part that is
 * misbehaving instead of drowning the log in browser paint noise while chasing
 * an encoder problem.</p>
 *
 * <p><b>Nothing logged through these may contain a stream key.</b> Anything
 * derived from a publish URL, from FFmpeg output, or from an exception raised
 * by a network operation must go through
 * {@link dev.streamable.util.SecretRedactor} first.</p>
 */
public final class StreamAbleLog {

    public static final Logger CORE = LoggerFactory.getLogger("Stream-able");
    public static final Logger RECORDING = LoggerFactory.getLogger("Stream-able/Recording");
    public static final Logger STREAMING = LoggerFactory.getLogger("Stream-able/Streaming");
    public static final Logger BROWSER = LoggerFactory.getLogger("Stream-able/Browser");
    public static final Logger AUDIO = LoggerFactory.getLogger("Stream-able/Audio");
    public static final Logger FFMPEG = LoggerFactory.getLogger("Stream-able/FFmpeg");
    public static final Logger COMPOSITOR = LoggerFactory.getLogger("Stream-able/Compositor");

    private StreamAbleLog() {
    }
}
