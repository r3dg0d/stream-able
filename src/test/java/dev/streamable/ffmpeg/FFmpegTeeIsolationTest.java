package dev.streamable.ffmpeg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FFmpegTeeIsolationTest {
    @TempDir Path directory;

    private static Path ffmpeg() {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.io.File.pathSeparator)) {
                Path candidate = Path.of(entry, "ffmpeg");
                if (Files.isExecutable(candidate)) return candidate;
            }
        }
        return null;
    }

    @Test
    @Timeout(25)
    void stalledDestinationDoesNotBlockHealthyOutput() throws Exception {
        Path executable = ffmpeg();
        assumeTrue(executable != null, "requires FFmpeg with libx264 and lavfi");
        Path healthy = directory.resolve("healthy.flv");
        Path errors = directory.resolve("stderr.txt");
        // This owned loopback peer accepts the connection but deliberately never reads.
        try (ServerSocket server = new ServerSocket()) {
            server.setReceiveBufferSize(1024);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            server.setSoTimeout(10_000);
            EncodeProfile profile = new EncodeProfile(
                    VideoProfile.liveDefault(VideoEncoder.X264, 640, 360, 60, 8000), AudioProfile.LIVE_DEFAULT);
            List<String> args = new ArrayList<>(FFmpegCommandBuilder.buildStreamCommand(
                    executable.toString(), profile, 640, 360,
                    List.of(healthy.toString(), "tcp://" + (server.getInetAddress().getHostAddress().contains(":")
                            ? "[" + server.getInetAddress().getHostAddress() + "]"
                            : server.getInetAddress().getHostAddress()) + ":" + server.getLocalPort()), 0, 0));
            // Only replace the raw capture input with paced synthetic frames. Production
            // encode settings and output options, including tee escaping, remain intact.
            int input = args.indexOf("rawvideo") - 1;
            int end = args.indexOf("pipe:0") + 1;
            args.subList(input, end).clear();
            args.addAll(input, List.of("-re", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=60"));
            args.addAll(args.size() - 1, List.of("-t", "8"));
            AtomicInteger frames = new AtomicInteger();
            Process process = new ProcessBuilder(args).redirectError(errors.toFile()).start();
            Thread reader = Thread.ofPlatform().daemon(true).start(() -> {
                try (var lines = process.inputReader()) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (line.startsWith("frame=")) frames.set(Integer.parseInt(line.substring(6).trim()));
                    }
                } catch (Exception ignored) { }
            });
            try (Socket stalled = server.accept()) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (frames.get() < 400 && process.isAlive() && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                assertTrue(frames.get() >= 400,
                        () -> "shared encoder stalled at " + frames.get() + " frames: " + read(errors));
                assertTrue(Files.size(healthy) > 2_000_000, "healthy output must keep receiving packets");
                assertTrue(read(errors).contains("FIFO queue full"), "test must exercise actual congestion");
            } finally {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                reader.join(1000);
            }
        }
    }

    @Test
    @Timeout(90)
    void uploadBudgetPreservesDeliveredAudioAndVideoAcrossFourThrottledOutputs() throws Exception {
        Path executable = ffmpeg();
        assumeTrue(executable != null, "requires FFmpeg and ffprobe");
        Path probe = executable.resolveSibling("ffprobe");
        assumeTrue(Files.isExecutable(probe), "requires ffprobe beside ffmpeg");
        // The unbudgeted control reproduces packet loss; the capped run must
        // deliver every frame and contiguous audio through the same receivers.
        for (boolean budgeted : List.of(false, true)) {
            List<ServerSocket> servers = new ArrayList<>();
            List<Thread> receivers = new ArrayList<>();
            List<Path> files = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Process process = null;
            Path errors = directory.resolve("four-" + budgeted + ".stderr");
            try {
                for (int i = 0; i < 4; i++) {
                    ServerSocket server = new ServerSocket();
                    server.setReceiveBufferSize(64 * 1024);
                    server.bind(new InetSocketAddress("127.0.0.1", 0));
                    server.setSoTimeout(10_000);
                    servers.add(server);
                    urls.add("tcp://127.0.0.1:" + server.getLocalPort());
                    Path received = directory.resolve("four-" + budgeted + "-" + i + ".flv");
                    files.add(received);
                    receivers.add(Thread.ofPlatform().daemon(true).start(() -> {
                        try (Socket socket = server.accept(); var input = socket.getInputStream();
                             var output = Files.newOutputStream(received)) {
                            socket.setSoTimeout(30_000);
                            byte[] buffer = new byte[8192];
                            long start = System.nanoTime(), bytes = 0;
                            int n;
                            while ((n = input.read(buffer)) >= 0) {
                                output.write(buffer, 0, n);
                                bytes += n;
                                // 5.6 Mbps per receiver: ~22.4 Mbps aggregate.
                                long due = start + (long) (bytes * 1_000_000_000.0 / 700_000);
                                long wait = due - System.nanoTime();
                                if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
                            }
                        } catch (Throwable e) { failure.compareAndSet(null, e); }
                    }));
                }
                EncodeProfile requested = new EncodeProfile(VideoProfile.liveDefault(VideoEncoder.X264,
                        640, 360, 60, 8000), AudioProfile.LIVE_DEFAULT);
                var destinations = java.util.stream.IntStream.range(0, 4).mapToObj(i ->
                        new dev.streamable.streaming.StreamDestination(java.util.UUID.randomUUID(), "Local " + i,
                                dev.streamable.streaming.StreamPlatform.CUSTOM,
                                new dev.streamable.streaming.StreamingCredentials("rtmp://local/app", "fixture"))).toList();
                var plan = dev.streamable.streaming.UploadBudget.plan(
                        List.of(new dev.streamable.streaming.DestinationGrouping.Group(requested, destinations)),
                        budgeted ? 22.47 : 0, true);
                List<String> args = new ArrayList<>(FFmpegCommandBuilder.buildStreamCommand(
                        executable.toString(), plan.groups().getFirst().profile(), 640, 360, urls, 0, 0));
                int input = args.indexOf("rawvideo") - 1;
                int end = args.indexOf("pipe:0") + 1;
                args.subList(input, end).clear();
                args.addAll(input, List.of("-re", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=60",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000"));
                args.remove("-an");
                args.addAll(args.size() - 1, List.of("-map", "1:a:0", "-c:a", "aac", "-b:a", "160k",
                        "-ar", "48000", "-ac", "2", "-t", "18"));
                process = new ProcessBuilder(args).redirectError(errors.toFile())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "encoding must finish under constrained upload");
                org.junit.jupiter.api.Assertions.assertEquals(0, process.exitValue(), read(errors));
                for (Thread receiver : receivers) receiver.join(3000);
                org.junit.jupiter.api.Assertions.assertNull(failure.get(), "receiver failed");
                assertTrue(receivers.stream().noneMatch(Thread::isAlive), "receivers must finish");
                org.junit.jupiter.api.Assertions.assertEquals(!budgeted, read(errors).contains("FIFO queue full"), read(errors));
                for (Path file : files) {
                    Path json = file.resolveSibling(file.getFileName() + ".json");
                    Process inspect = new ProcessBuilder(probe.toString(), "-v", "error", "-show_packets",
                            "-show_entries", "packet=stream_index,pts_time", "-of", "json", file.toString())
                            .redirectOutput(json.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    try {
                        assertTrue(inspect.waitFor(10, TimeUnit.SECONDS));
                        org.junit.jupiter.api.Assertions.assertEquals(0, inspect.exitValue());
                    } finally { if (inspect.isAlive()) inspect.destroyForcibly(); }
                    var packets = com.google.gson.JsonParser.parseString(Files.readString(json)).getAsJsonObject()
                            .getAsJsonArray("packets");
                    List<Double> video = new ArrayList<>(), audio = new ArrayList<>();
                    for (var element : packets) {
                        var packet = element.getAsJsonObject();
                        if (!packet.has("pts_time")) continue;
                        (packet.get("stream_index").getAsInt() == 0 ? video : audio)
                                .add(packet.get("pts_time").getAsDouble());
                    }
                    if (budgeted) {
                        org.junit.jupiter.api.Assertions.assertEquals(1080, video.size(), "every captured frame must arrive");
                        assertTrue(audio.size() >= 840, "complete audio must arrive");
                        video.sort(Double::compare); audio.sort(Double::compare);
                        for (int i = 1; i < video.size(); i++) assertTrue(video.get(i) - video.get(i - 1) < 0.025);
                        for (int i = 1; i < audio.size(); i++) assertTrue(audio.get(i) - audio.get(i - 1) < 0.025);
                    } else assertTrue(video.size() < 1080, "overloaded control must reproduce missing sections");
                }
            } finally {
                if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
                for (ServerSocket server : servers) server.close();
                for (Thread receiver : receivers) { receiver.interrupt(); receiver.join(1000); }
            }
        }
    }

    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (Exception e) { return e.toString(); }
    }
}
