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

    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (Exception e) { return e.toString(); }
    }
}
