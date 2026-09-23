package dev.streamable.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Scriptable in-memory HTTP server for downloader tests. */
final class FakeHttp implements HttpFetcher {

    final Map<URI, byte[]> files = new HashMap<>();
    final Map<URI, Integer> failuresBeforeSuccess = new HashMap<>();
    final Map<URI, Integer> status = new HashMap<>();
    final List<String> requests = new ArrayList<>();
    boolean honourRanges = true;
    /** When set, the stream throws after this many bytes, simulating a dropped connection. */
    long dropAfterBytes = -1;

    FakeHttp serve(String url, byte[] content) {
        files.put(URI.create(url), content);
        return this;
    }

    @Override
    public Response open(URI uri, long rangeStart) throws IOException {
        requests.add(uri + "@" + rangeStart);
        int remainingFailures = failuresBeforeSuccess.getOrDefault(uri, 0);
        if (remainingFailures > 0) {
            failuresBeforeSuccess.put(uri, remainingFailures - 1);
            throw new IOException("simulated connection reset");
        }
        Integer forced = status.get(uri);
        if (forced != null) {
            return new Response(forced, 0, InputStream.nullInputStream());
        }
        byte[] data = files.get(uri);
        if (data == null) {
            return new Response(404, 0, InputStream.nullInputStream());
        }
        int offset = 0;
        int code = 200;
        if (rangeStart > 0 && honourRanges) {
            if (rangeStart >= data.length) {
                return new Response(416, 0, InputStream.nullInputStream());
            }
            offset = (int) rangeStart;
            code = 206;
        }
        byte[] body = java.util.Arrays.copyOfRange(data, offset, data.length);
        InputStream stream = new ByteArrayInputStream(body);
        if (dropAfterBytes >= 0) {
            long limit = dropAfterBytes;
            dropAfterBytes = -1;   // only the first request drops
            stream = new InputStream() {
                private final InputStream delegate = new ByteArrayInputStream(body);
                private long served;

                @Override
                public int read() throws IOException {
                    if (served >= limit) {
                        throw new IOException("simulated drop");
                    }
                    served++;
                    return delegate.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (served >= limit) {
                        throw new IOException("simulated drop");
                    }
                    int read = delegate.read(b, off, (int) Math.min(len, limit - served));
                    if (read > 0) {
                        served += read;
                    }
                    return read;
                }
            };
        }
        return new Response(code, body.length, stream);
    }
}
