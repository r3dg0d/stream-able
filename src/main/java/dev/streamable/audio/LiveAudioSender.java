package dev.streamable.audio;

import dev.streamable.StreamAbleLog;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Carries the live PCM program mix to FFmpeg over a loopback socket.
 *
 * <h2>Why a socket</h2>
 * <p>An FFmpeg process has one stdin, and the compositor already owns it for
 * raw video. Live audio needs a second input, and the portable options are
 * limited: named pipes do not exist on Windows in a form Java can create, and a
 * temporary file cannot be muxed while it is still being written. A loopback
 * TCP connection behaves identically on Windows and Linux, needs no native
 * code, and lets FFmpeg treat the audio as a normal continuous input.</p>
 *
 * <p>Stream-able <em>listens</em> and FFmpeg connects, so the port is chosen by
 * the OS ({@code port 0}) and there is no race or fixed-port conflict.</p>
 *
 * <h2>Timing</h2>
 * <p>No timestamps are sent: the stream is raw {@code s16le}, so FFmpeg derives
 * presentation times from the byte count. That makes the audio clock exact by
 * construction, provided the mixer delivers a continuous stream at the nominal
 * sample rate - which is what {@link AudioMixer} guarantees by filling gaps with
 * silence rather than letting the stream go sparse.</p>
 */
public final class LiveAudioSender implements AutoCloseable {

    private static final int QUEUE_CHUNKS = 64;

    private final ServerSocket serverSocket;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CHUNKS);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong chunksDropped = new AtomicLong();
    private volatile Socket clientSocket;
    private volatile boolean connected;
    private final Thread acceptThread;

    public LiveAudioSender() throws IOException {
        serverSocket = new ServerSocket();
        // Loopback only: this carries the player's microphone, so it must never
        // be reachable from the network.
        serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        serverSocket.setSoTimeout(30_000);

        acceptThread = Thread.ofPlatform()
                .name("stream-able-audio-accept")
                .daemon(true)
                .start(this::acceptAndPump);
    }

    /** The port FFmpeg must connect to. */
    public int port() {
        return serverSocket.getLocalPort();
    }

    public boolean isConnected() {
        return connected;
    }

    public long bytesSent() {
        return bytesSent.get();
    }

    public long chunksDropped() {
        return chunksDropped.get();
    }

    /**
     * Queues a PCM chunk. Never blocks the audio thread; a chunk is dropped if
     * FFmpeg has stalled, which is preferable to backing up the mixer.
     */
    public void write(byte[] pcm) {
        if (!running.get() || pcm == null || pcm.length == 0) {
            return;
        }
        if (!queue.offer(pcm)) {
            chunksDropped.incrementAndGet();
        }
    }

    private void acceptAndPump() {
        try (Socket socket = serverSocket.accept()) {
            clientSocket = socket;
            socket.setTcpNoDelay(true);
            connected = true;
            StreamAbleLog.AUDIO.debug("FFmpeg connected to the live audio channel.");
            OutputStream out = socket.getOutputStream();
            while (running.get()) {
                byte[] chunk = queue.poll(200, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    continue;
                }
                out.write(chunk);
                bytesSent.addAndGet(chunk.length);
            }
            out.flush();
        } catch (IOException e) {
            if (running.get()) {
                StreamAbleLog.AUDIO.warn("Live audio channel closed: {}", e.toString());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            connected = false;
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            Socket socket = clientSocket;
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            StreamAbleLog.AUDIO.debug("Error closing the audio socket: {}", e.toString());
        }
        try {
            serverSocket.close();
        } catch (IOException e) {
            StreamAbleLog.AUDIO.debug("Error closing the audio listener: {}", e.toString());
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }
}
