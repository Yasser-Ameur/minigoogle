package com.minigoogle.crawler.downloader;

import com.minigoogle.crawler.model.DownloadedPage;
import com.minigoogle.crawler.model.UrlTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * HTTP client with redirect handling and GZIP decompression for fetching web pages.
 * Follows up to a configurable number of redirects and transparently decodes
 * compressed responses before returning the downloaded content.
 */
public class HttpDownloader implements Downloader {

    private static final Logger logger = LoggerFactory.getLogger(HttpDownloader.class);
    private static final String USER_AGENT = "MiniGoogleBot/1.0";
    private static final int MAX_REDIRECTS = 5;

    private final HttpClient client;

    public HttpDownloader() {
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public DownloadedPage download(UrlTask task) {
        URI currentUri = URI.create(task.normalizedUrl());
        int redirects = 0;

        while (redirects <= MAX_REDIRECTS) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(currentUri)
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Encoding", "gzip, deflate")
                    .GET()
                    .build();

                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int statusCode = response.statusCode();

                if (isRedirect(statusCode)) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    if (location == null) {
                        logger.warn("Redirect without Location header for URL: {}", currentUri);
                        return null;
                    }

                    currentUri = currentUri.resolve(location);
                    redirects++;
                    logger.debug("Redirecting to: {}", currentUri);
                    continue;
                }

                Map<String, String> headers = new HashMap<>();
                response.headers().map().forEach((k, v) -> headers.put(k, String.join(", ", v)));

                String body = decompressResponse(response);

                return new DownloadedPage(
                    currentUri,
                    statusCode,
                    body,
                    headers,
                    Instant.now()
                );

            } catch (Exception e) {
                logger.error("Failed to download URL: {} - {}", currentUri, e.getMessage());
                return null;
            }
        }

        logger.warn("Exceeded max redirects for URL: {}", task.normalizedUrl());
        return null;
    }

    private String decompressResponse(HttpResponse<byte[]> response) throws Exception {
        byte[] bodyBytes = response.body();
        String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("");

        if (contentEncoding.contains("gzip")) {
            try (GZIPInputStream gzipInputStream = new GZIPInputStream(new java.io.ByteArrayInputStream(bodyBytes));
                 ByteArrayOutputStream resultStream = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = gzipInputStream.read(buffer)) != -1) {
                    resultStream.write(buffer, 0, length);
                }
                return resultStream.toString("UTF-8");
            }
        }

        return new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 307 || statusCode == 308;
    }
}
