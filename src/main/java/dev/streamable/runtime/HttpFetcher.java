package dev.streamable.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal HTTP abstraction so downloads can be tested without a network.
 *
 * <p>The production implementation refuses anything but HTTPS, including after
 * redirects: {@link HttpClient.Redirect#NORMAL} already never downgrades
 * HTTPS to HTTP, and the final URI is re-checked anyway.</p>
 */
public interface HttpFetcher {

    /**
     * Opens a GET request.
     *
     * @param uri        the resource
     * @param rangeStart byte offset to resume from, or {@code 0} for the whole file
     */
    Response open(URI uri, long rangeStart) throws IOException;

    /**
     * An open response. The body must be closed by the caller.
     *
     * @param status        HTTP status code
     * @param contentLength length of <em>this response's</em> body, or {@code -1}
     * @param body          the body stream
     */
    record Response(int status, long contentLength, InputStream body) implements AutoCloseable {
        public boolean isPartial() {
            return status == 206;
        }

        public boolean isOk() {
            return status == 200 || status == 206;
        }

        @Override
        public void close() throws IOException {
            if (body != null) {
                body.close();
            }
        }
    }

    /** The real HTTPS transport. */
    static HttpFetcher https(String userAgent) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        return (uri, rangeStart) -> {
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Refusing non-HTTPS download: " + uri.getScheme());
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(30))
                    .header("User-Agent", userAgent)
                    .GET();
            if (rangeStart > 0) {
                request.header("Range", "bytes=" + rangeStart + "-");
            }
            try {
                HttpResponse<InputStream> response = client.send(request.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (!"https".equalsIgnoreCase(response.uri().getScheme())) {
                    response.body().close();
                    throw new IOException("Download was redirected to a non-HTTPS location");
                }
                long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                return new Response(response.statusCode(), length, response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", e);
            }
        };
    }
}
