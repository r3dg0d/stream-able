package dev.streamable.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDownloaderTest {

    @TempDir
    Path dir;

    private static final String URL = "https://mirror-a.example/file.bin";
    private static final String URL_B = "https://mirror-b.example/file.bin";

    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        return data;
    }

    private static RuntimeArtifact artifact(byte[] data, List<String> urls, boolean pinSize) {
        return new RuntimeArtifact(RuntimePlatform.ANY, urls.stream().map(URI::create).toList(),
                Sha256.hash(data), pinSize ? data.length : -1, RuntimeArtifact.Format.FILE,
                "file.bin", 0, List.of(), List.of());
    }

    private static RuntimeDownloader downloader(FakeHttp http) {
        return new RuntimeDownloader(http, 3, 1, millis -> { });
    }

    @Test
    void downloadsAndVerifies() throws IOException {
        byte[] data = payload(700_000);
        FakeHttp http = new FakeHttp().serve(URL, data);
        AtomicInteger reports = new AtomicInteger();
        Path file = downloader(http).download(artifact(data, List.of(URL), true), dir,
                (done, total) -> reports.incrementAndGet(), () -> false);
        assertArrayEquals(data, Files.readAllBytes(file));
        assertFalse(Files.exists(dir.resolve("file.bin.part")));
        assertTrue(reports.get() > 1, "progress should be reported while downloading");
    }

    @Test
    void wrongChecksumIsRejectedAndNothingIsKept() {
        byte[] data = payload(10_000);
        byte[] tampered = data.clone();
        tampered[5000] ^= 1;
        FakeHttp http = new FakeHttp().serve(URL, tampered);
        IOException error = assertThrows(IOException.class,
                () -> downloader(http).download(artifact(data, List.of(URL), true), dir, (a, b) -> { }, () -> false));
        assertTrue(error.getMessage().contains("SHA-256 mismatch"), error.getMessage());
        assertFalse(Files.exists(dir.resolve("file.bin")));
        assertFalse(Files.exists(dir.resolve("file.bin.part")));
    }

    @Test
    void resumesAnInterruptedDownloadWithARangeRequest() throws IOException {
        byte[] data = payload(500_000);
        FakeHttp http = new FakeHttp().serve(URL, data);
        http.dropAfterBytes = 200_000;
        Path file = downloader(http).download(artifact(data, List.of(URL), true), dir, (a, b) -> { }, () -> false);
        assertArrayEquals(data, Files.readAllBytes(file));
        assertEquals(URL + "@0", http.requests.get(0));
        assertTrue(http.requests.get(1).endsWith("@200000"), "second request resumes: " + http.requests);
    }

    @Test
    void resumesAPartialLeftByAPreviousSession() throws IOException {
        byte[] data = payload(300_000);
        Files.write(dir.resolve("file.bin.part"), java.util.Arrays.copyOf(data, 120_000));
        FakeHttp http = new FakeHttp().serve(URL, data);
        Path file = downloader(http).download(artifact(data, List.of(URL), true), dir, (a, b) -> { }, () -> false);
        assertArrayEquals(data, Files.readAllBytes(file));
        assertEquals(List.of(URL + "@120000"), http.requests);
    }

    @Test
    void restartsCleanlyWhenTheServerIgnoresRanges() throws IOException {
        byte[] data = payload(300_000);
        Files.write(dir.resolve("file.bin.part"), java.util.Arrays.copyOf(data, 100_000));
        FakeHttp http = new FakeHttp().serve(URL, data);
        http.honourRanges = false;
        Path file = downloader(http).download(artifact(data, List.of(URL), true), dir, (a, b) -> { }, () -> false);
        assertArrayEquals(data, Files.readAllBytes(file));
    }

    @Test
    void corruptPartialIsDiscardedAndRedownloaded() throws IOException {
        byte[] data = payload(200_000);
        byte[] garbage = payload(200_000);
        garbage[10] ^= 0x55;
        Files.write(dir.resolve("file.bin.part"), garbage);   // same size, wrong bytes
        FakeHttp http = new FakeHttp().serve(URL, data);
        Path file = downloader(http).download(artifact(data, List.of(URL), true), dir, (a, b) -> { }, () -> false);
        assertArrayEquals(data, Files.readAllBytes(file));
    }

    @Test
    void retriesTransientFailures() throws IOException {
        byte[] data = payload(1_000);
        FakeHttp http = new FakeHttp().serve(URL, data);
        http.failuresBeforeSuccess.put(URI.create(URL), 2);
        Path file = downloader(http).download(artifact(data, List.of(URL), true), dir, (a, b) -> { }, () -> false);
        assertArrayEquals(data, Files.readAllBytes(file));
        assertEquals(3, http.requests.size());
    }

    @Test
    void fallsBackToTheNextMirror() throws IOException {
        byte[] data = payload(1_000);
        FakeHttp http = new FakeHttp().serve(URL_B, data);
        http.status.put(URI.create(URL), 503);
        Path file = downloader(http).download(artifact(data, List.of(URL, URL_B), true), dir, (a, b) -> { }, () -> false);
        assertArrayEquals(data, Files.readAllBytes(file));
        assertTrue(http.requests.stream().anyMatch(r -> r.startsWith(URL_B)));
    }

    @Test
    void failsAfterExhaustingMirrors() {
        byte[] data = payload(1_000);
        FakeHttp http = new FakeHttp();
        IOException error = assertThrows(IOException.class,
                () -> downloader(http).download(artifact(data, List.of(URL, URL_B), true), dir, (a, b) -> { }, () -> false));
        assertTrue(error.getMessage().contains("404"), error.getMessage());
        assertEquals(6, http.requests.size(), "3 attempts per mirror");
    }

    @Test
    void cancellationStopsImmediately() {
        byte[] data = payload(1_000);
        FakeHttp http = new FakeHttp().serve(URL, data);
        assertThrows(RuntimeDownloader.CancelledException.class,
                () -> downloader(http).download(artifact(data, List.of(URL), true), dir, (a, b) -> { }, () -> true));
        assertTrue(http.requests.isEmpty());
    }

    @Test
    void oversizedResponseIsRejected() {
        byte[] data = payload(1_000);
        byte[] bigger = payload(2_000);
        FakeHttp http = new FakeHttp().serve(URL, bigger);
        assertThrows(IOException.class,
                () -> downloader(http).download(artifact(data, List.of(URL), true), dir, (a, b) -> { }, () -> false));
        assertFalse(Files.exists(dir.resolve("file.bin")));
    }

    @Test
    void existingVerifiedFileIsReusedWithoutNetwork() throws IOException {
        byte[] data = payload(1_000);
        Files.write(dir.resolve("file.bin"), data);
        FakeHttp http = new FakeHttp();
        downloader(http).download(artifact(data, List.of(URL), true), dir, (a, b) -> { }, () -> false);
        assertTrue(http.requests.isEmpty());
    }

    @Test
    void existingTamperedFileIsReplaced() throws IOException {
        byte[] data = payload(1_000);
        Files.write(dir.resolve("file.bin"), payload(999));
        FakeHttp http = new FakeHttp().serve(URL, data);
        Path file = downloader(http).download(artifact(data, List.of(URL), false), dir, (a, b) -> { }, () -> false);
        assertArrayEquals(data, Files.readAllBytes(file));
    }
}
