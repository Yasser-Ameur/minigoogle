package com.minigoogle.crawler.downloader;

import com.minigoogle.crawler.model.DownloadedPage;
import com.minigoogle.crawler.model.UrlTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * HTTP client with redirect handling, bounded GZIP/DEFLATE decompression, and
 * SSRF protection for fetching web pages.
 *
 * <p>Every destination (the seed URL and each redirect hop) is validated by a
 * {@link NetworkSafetyPolicy} before a request is sent, so internal/metadata
 * endpoints can never be reached. Response bodies are read with hard caps on
 * both the compressed wire bytes and the decompressed size, and a maximum
 * number of redirects is enforced.</p>
 *
 * <p>All failures are reported as {@code null} with a log entry; no internal
 * networking details are ever propagated to callers.</p>
 */
public class HttpDownloader implements Downloader {

    private static final Logger logger = LoggerFactory.getLogger(HttpDownloader.class);
    private static final String USER_AGENT = "MiniGoogleBot/1.0";
    private static final int MAX_REDIRECTS = 5;
    private static final long DEFAULT_MAX_COMPRESSED_BYTES = 10 * 1024 * 1024;
    private static final long DEFAULT_MAX_DECOMPRESSED_BYTES = 50 * 1024 * 1024;

    private final HttpClient client;
    private final NetworkSafetyPolicy safetyPolicy;
    private final long maxCompressedBytes;
    private final long maxDecompressedBytes;

    public HttpDownloader() {
        this(new NetworkSafetyPolicy());
    }

    /**
     * @param safetyPolicy the SSRF policy to enforce on every hop
     */
    public HttpDownloader(NetworkSafetyPolicy safetyPolicy) {
        this(safetyPolicy, DEFAULT_MAX_COMPRESSED_BYTES, DEFAULT_MAX_DECOMPRESSED_BYTES);
    }

    HttpDownloader(NetworkSafetyPolicy safetyPolicy, long maxCompressedBytes, long maxDecompressedBytes) {
        this.safetyPolicy = safetyPolicy;
        this.maxCompressedBytes = maxCompressedBytes;
        this.maxDecompressedBytes = maxDecompressedBytes;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public DownloadedPage download(UrlTask task) {
        URI currentUri = parseUri(task.normalizedUrl());
        if (currentUri == null) {
            return null;
        }

        int redirects = 0;
        while (true) {
            if (!safetyPolicy.isSafe(currentUri)) {
                logger.warn("Blocked unsafe destination: {}", currentUri);
                return null;
            }

            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(currentUri)
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Encoding", "gzip, deflate")
                    .GET()
                    .build();

                HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                try (InputStream bodyStream = response.body()) {
                    int statusCode = response.statusCode();

                    if (isRedirect(statusCode)) {
                        if (redirects >= MAX_REDIRECTS) {
                            logger.warn("Exceeded max redirects ({}) for URL: {}", MAX_REDIRECTS, task.normalizedUrl());
                            return null;
                        }

                        String location = response.headers().firstValue("Location").orElse(null);
                        if (location == null) {
                            logger.warn("Redirect without Location header for URL: {}", currentUri);
                            return null;
                        }

                        URI resolved = currentUri.resolve(location);
                        if (resolved.getScheme() == null || resolved.getHost() == null) {
                            logger.warn("Redirect to malformed location {} from URL: {}", location, currentUri);
                            return null;
                        }

                        currentUri = resolved;
                        redirects++;
                        logger.debug("Redirecting to: {}", currentUri);
                        continue;
                    }

                    if (statusCode < 200 || statusCode >= 300) {
                        logger.warn("Skipping non-success response status {} for URL: {}", statusCode, currentUri);
                        return null;
                    }

                    Map<String, String> headers = new HashMap<>();
                    response.headers().map().forEach((k, v) -> headers.put(k, String.join(", ", v)));

                    String contentEncoding =
                        response.headers().firstValue("Content-Encoding").orElse("");
                    String body = readBoundedBody(bodyStream, contentEncoding);

                    return new DownloadedPage(
                        currentUri,
                        statusCode,
                        body,
                        headers,
                        Instant.now()
                    );
                }
            } catch (Exception e) {
                logger.error("Failed to download URL: {} - {}", currentUri, e.getMessage());
                return null;
            }
        }
    }

    /**
     * Reads the response body with a hard cap on wire bytes, then decodes
     * GZIP/DEFLATE with a hard cap on the decompressed size. Exceeding either
     * cap throws, which the caller turns into a failed download.
     */
    private String readBoundedBody(InputStream bodyStream, String contentEncoding) throws IOException {
        byte[] wireBytes = readBounded(bodyStream, maxCompressedBytes);

        if (contentEncoding.contains("gzip")) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(wireBytes))) {
                return new String(readBounded(gzip, maxDecompressedBytes), StandardCharsets.UTF_8);
            }
        }
        if (contentEncoding.contains("deflate")) {
            try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(wireBytes))) {
                return new String(readBounded(inflater, maxDecompressedBytes), StandardCharsets.UTF_8);
            }
        }

        return new String(wireBytes, StandardCharsets.UTF_8);
    }

    private static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int length;
        while ((length = in.read(buffer)) != -1) {
            total += length;
            if (total > maxBytes) {
                throw new IOException("Response body exceeds limit of " + maxBytes + " bytes");
            }
            out.write(buffer, 0, length);
        }
        return out.toByteArray();
    }

    private static URI parseUri(String url) {
        try {
            URI uri = URI.create(url);
            return (uri.getScheme() == null || uri.getHost() == null) ? null : uri;
        } catch (IllegalArgumentException e) {
            logger.warn("Malformed URL rejected: {}", url);
            return null;
        }
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }
}
