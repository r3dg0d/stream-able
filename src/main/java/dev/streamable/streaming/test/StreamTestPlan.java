package dev.streamable.streaming.test;

import dev.streamable.streaming.StreamDestination;
import dev.streamable.streaming.StreamPlatform;

import java.util.Locale;

/**
 * What a destination test can safely do, stated honestly.
 *
 * <p>Twitch documents a bandwidth-test mode: appending {@code ?bandwidthtest=true}
 * to the stream key makes the ingest accept and measure the stream without
 * making the channel live. For every other destination no such mode is known,
 * so the encoder is tested locally and only connectivity is checked remotely.</p>
 */
public record StreamTestPlan(Mode mode, String explanation) {

    public enum Mode {
        /** Publish to the service's own non-public test mode. */
        SERVICE_BANDWIDTH_TEST,
        /** Encode for real into a local sink; probe the ingest's connectivity only. */
        LOCAL_ENCODER_TEST
    }

    public static StreamTestPlan forDestination(StreamDestination destination) {
        String url = destination.credentials().ingestUrl().toLowerCase(Locale.ROOT);
        boolean twitch = destination.platform() == StreamPlatform.TWITCH || url.contains(".twitch.tv/")
                || url.contains("://live.twitch.tv");
        if (twitch && !destination.credentials().streamKey().isEmpty()) {
            return new StreamTestPlan(Mode.SERVICE_BANDWIDTH_TEST,
                    "Twitch bandwidth-test mode: the stream is sent to Twitch with ?bandwidthtest=true, "
                            + "which Twitch accepts without making your channel live. Upload bitrate is measured "
                            + "end to end.");
        }
        return new StreamTestPlan(Mode.LOCAL_ENCODER_TEST,
                "Encoder and local network performance can be tested, but this destination does not provide "
                        + "a known non-public RTMP ingest test mode. Stream-able checks that the server is "
                        + "reachable and speaks RTMP, then encodes locally at your settings; nothing is published.");
    }

    /** The publish URL for the service test mode. */
    static String bandwidthTestUrl(StreamDestination destination) {
        String url = destination.credentials().publishUrl();
        return url + (url.contains("?") ? "&" : "?") + "bandwidthtest=true";
    }
}
