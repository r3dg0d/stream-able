package dev.streamable.streaming.test;

import dev.streamable.streaming.StreamPlatform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Optional handshake-only reachability of the default Twitch / YouTube / X ingest
 * hosts. Never sends a stream key or publish command ({@link NetworkProbe}).
 *
 * <p>Skipped unless {@code STREAMABLE_LIVE_INGEST=1} so CI stays offline-safe.
 * Run locally: {@code STREAMABLE_LIVE_INGEST=1 ./gradlew test --tests '*LiveIngestReachabilityTest*'}.</p>
 */
@EnabledIfEnvironmentVariable(named = "STREAMABLE_LIVE_INGEST", matches = "1")
class LiveIngestReachabilityTest {

    private static final NetworkProbe PROBE = new NetworkProbe(8_000);

    @Test
    @DisplayName("Twitch default RTMP answers the handshake")
    void twitchDefaultIngest() {
        assertHandshake(StreamPlatform.TWITCH.defaultIngestUrl(), false);
    }

    @Test
    @DisplayName("YouTube default RTMPS answers TLS + handshake")
    void youtubeDefaultIngest() {
        assertHandshake(StreamPlatform.YOUTUBE.defaultIngestUrl(), true);
    }

    @Test
    @DisplayName("X default RTMPS answers TLS + handshake")
    void xDefaultIngest() {
        assertHandshake(StreamPlatform.X.defaultIngestUrl(), true);
    }

    @Test
    @DisplayName("legacy YouTube plain RTMP still answers (documented fallback)")
    void youtubePlainRtmpFallback() {
        assertHandshake("rtmp://a.rtmp.youtube.com/live2", false);
    }

    private static void assertHandshake(String url, boolean expectTls) {
        List<NetworkProbe.Check> checks;
        try {
            checks = PROBE.run(url, () -> false);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "network unavailable: " + e.getMessage());
            return;
        }
        Assumptions.assumeFalse(checks.isEmpty(), "no checks returned");
        Assumptions.assumeFalse(
                checks.getFirst().status() == NetworkProbe.Status.FAILED
                        && "DNS".equals(checks.getFirst().name()),
                "offline / DNS blocked: " + checks);
        List<String> names = checks.stream().map(NetworkProbe.Check::name).toList();
        assertTrue(names.contains("DNS") && names.contains("TCP") && names.contains("RTMP handshake"),
                "unexpected steps for " + url + ": " + checks);
        if (expectTls) {
            assertTrue(names.contains("TLS"), "expected TLS for " + url + ": " + checks);
        } else {
            assertTrue(!names.contains("TLS"), "plain RTMP must not report TLS: " + checks);
        }
        assertTrue(checks.stream().allMatch(c -> c.status() == NetworkProbe.Status.PASSED),
                "handshake-only probe failed for " + url + ": " + checks);
        assertEquals(NetworkProbe.Status.PASSED,
                checks.stream().filter(c -> c.name().equals("RTMP handshake")).findFirst().orElseThrow().status());
    }
}
