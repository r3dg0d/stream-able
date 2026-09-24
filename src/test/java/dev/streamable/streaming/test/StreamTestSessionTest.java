package dev.streamable.streaming.test;

import dev.streamable.ffmpeg.AudioProfile;
import dev.streamable.ffmpeg.EncodeProfile;
import dev.streamable.ffmpeg.VideoEncoder;
import dev.streamable.ffmpeg.VideoProfile;
import dev.streamable.streaming.StreamDestination;
import dev.streamable.streaming.StreamPlatform;
import dev.streamable.streaming.StreamingCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamTestSessionTest {

    private ServerSocket fakeRtmp;

    @AfterEach
    void close() throws IOException {
        if (fakeRtmp != null) {
            fakeRtmp.close();
        }
    }

    /** Answers the RTMP handshake and then waits: a server that never sees a publish. */
    private int startFakeRtmp() throws IOException {
        fakeRtmp = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon(true).start(() -> {
            while (!fakeRtmp.isClosed()) {
                try (Socket client = fakeRtmp.accept()) {
                    DataInputStream in = new DataInputStream(client.getInputStream());
                    OutputStream out = client.getOutputStream();
                    in.readUnsignedByte();
                    byte[] c1 = new byte[1536];
                    in.readFully(c1);
                    out.write(3);
                    out.write(new byte[1536]);
                    out.write(c1);
                    out.flush();
                    in.readFully(new byte[1536]);
                } catch (IOException e) {
                    return;
                }
            }
        });
        return fakeRtmp.getLocalPort();
    }

    private static StreamDestination destination(StreamPlatform platform, String url, String key) {
        return new StreamDestination(UUID.randomUUID(), "Test", platform, new StreamingCredentials(url, key));
    }

    private static EncodeProfile smallProfile() {
        return new EncodeProfile(VideoProfile.liveDefault(VideoEncoder.X264, 640, 360, 30, 1500), AudioProfile.LIVE_DEFAULT);
    }

    private static String ffmpeg() {
        for (String candidate : List.of("/run/current-system/sw/bin/ffmpeg", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg")) {
            if (java.nio.file.Files.isExecutable(java.nio.file.Path.of(candidate))) {
                return candidate;
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                java.nio.file.Path file = java.nio.file.Path.of(dir, "ffmpeg");
                if (java.nio.file.Files.isExecutable(file)) {
                    return file.toString();
                }
            }
        }
        return null;
    }

    private static void await(StreamTestSession session, int seconds) throws InterruptedException {
        for (int i = 0; i < seconds * 20 && session.isRunning(); i++) {
            Thread.sleep(50);
        }
    }

    @Test
    void twitchUsesItsNonPublicBandwidthTestMode() {
        StreamDestination twitch = destination(StreamPlatform.TWITCH, "rtmp://live.twitch.tv/app", "live_1_abc");
        StreamTestPlan plan = StreamTestPlan.forDestination(twitch);
        assertEquals(StreamTestPlan.Mode.SERVICE_BANDWIDTH_TEST, plan.mode());
        assertEquals("rtmp://live.twitch.tv/app/live_1_abc?bandwidthtest=true", StreamTestPlan.bandwidthTestUrl(twitch));
    }

    @Test
    void otherServicesNeverPublishDuringATest() {
        for (StreamPlatform platform : List.of(StreamPlatform.YOUTUBE, StreamPlatform.X, StreamPlatform.CUSTOM)) {
            StreamTestPlan plan = StreamTestPlan.forDestination(destination(platform, "rtmp://a.example/live", "k"));
            assertEquals(StreamTestPlan.Mode.LOCAL_ENCODER_TEST, plan.mode(), platform.name());
            assertTrue(plan.explanation().contains("does not provide a known non-public RTMP ingest test mode"));
        }
    }

    @Test
    void probeCompletesTheRtmpHandshakeWithoutPublishing() throws IOException {
        int port = startFakeRtmp();
        List<NetworkProbe.Check> checks = new NetworkProbe(3000).run("rtmp://127.0.0.1:" + port + "/app", () -> false);
        assertEquals(List.of("DNS", "TCP", "RTMP handshake"), checks.stream().map(NetworkProbe.Check::name).toList());
        assertTrue(checks.stream().allMatch(c -> c.status() == NetworkProbe.Status.PASSED), checks.toString());
    }

    @Test
    void connectionFailuresAreExplained() throws IOException {
        int closedPort;
        try (ServerSocket temp = new ServerSocket(0)) {
            closedPort = temp.getLocalPort();
        }
        List<NetworkProbe.Check> tcp = new NetworkProbe(2000).run("rtmp://127.0.0.1:" + closedPort + "/app", () -> false);
        assertEquals(NetworkProbe.Status.FAILED, tcp.getLast().status());
        assertTrue(tcp.getLast().detail().contains("firewall"));
        List<NetworkProbe.Check> dns = new NetworkProbe(2000).run("rtmp://no-such-host.invalid/app", () -> false);
        assertEquals("DNS", dns.getLast().name());
        assertEquals(NetworkProbe.Status.FAILED, dns.getLast().status());
    }

    @Test
    void invalidConfigurationFailsWithRedactedMessage() throws InterruptedException {
        StreamDestination bad = destination(StreamPlatform.CUSTOM, "rtmp://127.0.0.1:1/app", "live_12345_supersecretkey");
        StreamTestSession session = new StreamTestSession(bad, smallProfile(), "/definitely/not/ffmpeg", 5,
                new NetworkProbe(1000));
        session.start();
        await(session, 10);
        assertEquals(StreamTestSession.State.FAILED, session.state());
        assertFalse(session.message().contains("supersecretkey"), session.message());
        assertFalse(session.metrics().toString().contains("supersecretkey"));
    }

    @Test
    void localEncoderTestMeasuresRealEncoding() throws Exception {
        String ffmpeg = ffmpeg();
        Assumptions.assumeTrue(ffmpeg != null, "ffmpeg not installed");
        int port = startFakeRtmp();
        StreamDestination local = destination(StreamPlatform.CUSTOM, "rtmp://127.0.0.1:" + port + "/app", "key");
        StreamTestSession session = new StreamTestSession(local, smallProfile(), ffmpeg, 6, new NetworkProbe(3000));
        session.start();
        await(session, 40);
        StreamTestMetrics m = session.metrics();
        System.out.println("Local encoder test: " + m);
        assertEquals(StreamTestSession.State.COMPLETE, session.state(), session.message());
        assertTrue(m.encoderFps() > 20, "encoder fps " + m.encoderFps());
        assertTrue(m.averageKbps() > 100, "bitrate measured " + m.averageKbps());
        assertTrue(session.sinkBytes() > 10_000, "bytes reached the local sink, not a service");
        assertFalse(m.findings().isEmpty());
        assertFalse(session.childAlive(), "no orphan FFmpeg process");
    }

    @Test
    void cancellingMidTestKillsFfmpeg() throws Exception {
        String ffmpeg = ffmpeg();
        Assumptions.assumeTrue(ffmpeg != null, "ffmpeg not installed");
        int port = startFakeRtmp();
        StreamDestination local = destination(StreamPlatform.CUSTOM, "rtmp://127.0.0.1:" + port + "/app", "key");
        StreamTestSession session = new StreamTestSession(local, smallProfile(), ffmpeg, 60, new NetworkProbe(3000));
        session.start();
        for (int i = 0; i < 200 && session.state() != StreamTestSession.State.TESTING; i++) {
            Thread.sleep(20);
        }
        Thread.sleep(1500);
        session.cancel();
        assertEquals(StreamTestSession.State.CANCELLED, session.state());
        assertFalse(session.childAlive(), "FFmpeg child terminated on cancel");
    }

    @Test
    void findingsInterpretTheNumbers() {
        StreamTestMetrics slow = new StreamTestMetrics(6160, 3000, 3000, 42, 60, 250, 0.7, 12, 0.9, 0, 20, "",
                List.of(), List.of());
        List<String> findings = StreamTestSession.withFindings(slow, true).findings();
        assertTrue(findings.stream().anyMatch(f -> f.startsWith("Encoder cannot maintain 60 FPS")));
        assertTrue(findings.stream().anyMatch(f -> f.startsWith("Upload bandwidth is below the configured bitrate")));
        assertTrue(findings.stream().anyMatch(f -> f.contains("queue filled")));
        StreamTestMetrics good = new StreamTestMetrics(6160, 6100, 6150, 60, 60, 20, 1.0, 0, 0.05, 0, 20, "",
                List.of(), List.of());
        assertTrue(StreamTestSession.withFindings(good, true).findings().getFirst().startsWith("Your settings held"));
    }
}
