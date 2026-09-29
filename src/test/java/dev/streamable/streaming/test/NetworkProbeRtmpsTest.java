package dev.streamable.streaming.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local RTMPS fixture: TLS then the same RTMP handshake NetworkProbe uses against
 * real YouTube / X defaults. Never publishes; proves the probe's TLS path works.
 */
class NetworkProbeRtmpsTest {

    private static final char[] PASS = "streamable-test".toCharArray();

    private SSLServerSocket server;
    private final AtomicBoolean running = new AtomicBoolean(true);

    @TempDir
    Path temp;

    @AfterEach
    void close() throws IOException {
        running.set(false);
        if (server != null) {
            server.close();
        }
    }

    @Test
    void rtmpsCompletesTlsThenRtmpHandshakeWithoutPublishing() throws Exception {
        Path keystore = temp.resolve("rtmps.jks");
        generateKeystore(keystore);

        KeyStore ks = KeyStore.getInstance("JKS");
        try (var in = Files.newInputStream(keystore)) {
            ks.load(in, PASS);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS);
        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), null, new SecureRandom());

        SSLServerSocketFactory ssf = serverCtx.getServerSocketFactory();
        server = (SSLServerSocket) ssf.createServerSocket(0, 5, InetAddress.getLoopbackAddress());
        int port = server.getLocalPort();
        Thread.ofPlatform().daemon(true).start(() -> {
            while (running.get() && !server.isClosed()) {
                try (var client = server.accept()) {
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

        List<NetworkProbe.Check> checks = new NetworkProbe(3000, trustingFactory())
                .run("rtmps://127.0.0.1:" + port + "/app", () -> false);
        assertEquals(List.of("DNS", "TCP", "TLS", "RTMP handshake"),
                checks.stream().map(NetworkProbe.Check::name).toList(), checks.toString());
        assertTrue(checks.stream().allMatch(c -> c.status() == NetworkProbe.Status.PASSED), checks.toString());
        assertTrue(checks.stream().anyMatch(c -> c.name().equals("TLS") && c.detail().contains("certificate")),
                checks.toString());
    }

    private static void generateKeystore(Path keystore) throws IOException, InterruptedException {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path keytool = javaHome.resolve("bin").resolve("keytool");
        if (!Files.isExecutable(keytool)) {
            keytool = javaHome.resolve("bin").resolve("keytool.exe");
        }
        ProcessBuilder pb = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias", "local",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-storetype", "JKS",
                "-keystore", keystore.toString(),
                "-storepass", new String(PASS),
                "-keypass", new String(PASS),
                "-dname", "CN=127.0.0.1");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("keytool failed (" + code + "): " + out);
        }
    }

    private static SSLSocketFactory trustingFactory() throws Exception {
        TrustManager[] trustAll = {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {
            }

            public void checkServerTrusted(X509Certificate[] c, String a) {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());
        return ctx.getSocketFactory();
    }
}
