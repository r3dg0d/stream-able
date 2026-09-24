package dev.streamable.streaming.test;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Connectivity checks that never publish anything.
 *
 * <p>DNS resolution, a TCP connection, the TLS handshake for RTMPS, and the
 * RTMP handshake (C0/C1 - S0/S1/S2 - C2). The RTMP handshake is the
 * transport-level greeting every RTMP connection starts with; it carries no
 * application name, stream key or publish command, so a server cannot start a
 * broadcast from it. The socket is closed right after.</p>
 */
public final class NetworkProbe {

    public enum Status { PASSED, FAILED, SKIPPED }

    public record Check(String name, Status status, String detail, double millis) {
    }

    private final int timeoutMillis;

    public NetworkProbe(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /** Runs every applicable check in order, stopping at the first failure. */
    public List<Check> run(String ingestUrl, java.util.function.BooleanSupplier cancelled) {
        List<Check> checks = new ArrayList<>();
        URI uri;
        try {
            uri = URI.create(ingestUrl.trim());
        } catch (IllegalArgumentException e) {
            checks.add(new Check("Address", Status.FAILED, "The stream URL is not a valid address.", 0));
            return checks;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            checks.add(new Check("Address", Status.FAILED, "The stream URL has no host name.", 0));
            return checks;
        }
        boolean tls = scheme.equals("rtmps");
        boolean rtmp = scheme.equals("rtmp") || tls;
        int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : (rtmp ? 1935 : -1));

        long start = System.nanoTime();
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
            checks.add(new Check("DNS", Status.PASSED, host + " resolves to " + address.getHostAddress(), ms(start)));
        } catch (UnknownHostException e) {
            checks.add(new Check("DNS", Status.FAILED, "Could not resolve " + host + ". Check the URL and your connection.", ms(start)));
            return checks;
        }
        if (cancelled.getAsBoolean() || port < 0) {
            if (port < 0) {
                checks.add(new Check("Connection", Status.SKIPPED, "Only RTMP and RTMPS destinations can be probed.", 0));
            }
            return checks;
        }

        start = System.nanoTime();
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(address, port), timeoutMillis);
            raw.setSoTimeout(timeoutMillis);
            checks.add(new Check("TCP", Status.PASSED, "Connected to port " + port + ".", ms(start)));
            Socket channel = raw;
            if (tls) {
                start = System.nanoTime();
                SSLSocket secure = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(raw, host, port, true);
                secure.setSoTimeout(timeoutMillis);
                secure.startHandshake();
                checks.add(new Check("TLS", Status.PASSED, "Secure connection (" + secure.getSession().getProtocol()
                        + ", certificate valid for " + host + ").", ms(start)));
                channel = secure;
            }
            if (rtmp && !cancelled.getAsBoolean()) {
                start = System.nanoTime();
                rtmpHandshake(channel.getInputStream(), channel.getOutputStream());
                checks.add(new Check("RTMP handshake", Status.PASSED,
                        "The server speaks RTMP (handshake only; nothing was published).", ms(start)));
            }
        } catch (javax.net.ssl.SSLException e) {
            checks.add(new Check("TLS", Status.FAILED, "The secure connection failed: " + e.getMessage(), ms(start)));
        } catch (IOException e) {
            String step = checks.stream().anyMatch(c -> c.name().equals("TCP")) ? "RTMP handshake" : "TCP";
            checks.add(new Check(step, Status.FAILED, (step.equals("TCP")
                    ? "Could not connect to " + host + ":" + port + " (" + e.getMessage() + "). A firewall or a wrong port is likely."
                    : "The server did not complete the RTMP handshake (" + e.getMessage() + ")."), ms(start)));
        }
        return checks;
    }

    /** Client side of the RTMP 3.0 simple handshake. */
    static void rtmpHandshake(InputStream in, OutputStream out) throws IOException {
        byte[] c1 = new byte[1536];
        new Random().nextBytes(c1);
        for (int i = 0; i < 8; i++) {
            c1[i] = 0;                                  // time + zero fields
        }
        out.write(3);                                   // C0: RTMP version 3
        out.write(c1);
        out.flush();
        DataInputStream data = new DataInputStream(in);
        int version = data.readUnsignedByte();          // S0
        if (version != 3) {
            throw new IOException("unexpected RTMP version " + version);
        }
        byte[] s1 = new byte[1536];
        data.readFully(s1);                              // S1
        byte[] s2 = new byte[1536];
        data.readFully(s2);                              // S2 (echo of C1)
        out.write(s1);                                  // C2: echo S1
        out.flush();
    }

    private static double ms(long start) {
        return (System.nanoTime() - start) / 1e6;
    }
}
