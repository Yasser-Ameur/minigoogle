package com.minigoogle.crawler.downloader;

import com.minigoogle.crawler.model.DownloadedPage;
import com.minigoogle.crawler.model.UrlTask;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpDownloaderTest {

    private static HttpServer server;
    private static int port;
    private static final HttpDownloader downloader = new HttpDownloader(new NetworkSafetyPolicy(true));

    @BeforeAll
    static void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/ok", exchange -> respond(exchange, 200, "<html>ok</html>"));
        server.createContext("/missing", exchange -> respond(exchange, 404, "not found"));
        server.createContext("/error", exchange -> respond(exchange, 500, "boom"));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://localhost:" + port + "/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirect-to-internal", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirect-loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://localhost:" + port + "/redirect-loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/gzip-bomb", exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, 0);
            try (GZIPOutputStream gz = new GZIPOutputStream(exchange.getResponseBody())) {
                byte[] chunk = new byte[8192];
                for (int i = 0; i < 8192; i++) {
                    gz.write(chunk);
                }
            }
        });

        server.setExecutor(null);
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.sendResponseHeaders(status, body.length());
        OutputStream os = exchange.getResponseBody();
        os.write(body.getBytes());
        os.close();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private UrlTask task(String path) {
        return new UrlTask("http://localhost:" + port + path, "localhost", 0, Instant.now());
    }

    @Test
    void downloadsSuccessfulPage() {
        DownloadedPage page = downloader.download(task("/ok"));
        assertNotNull(page);
        assertEquals(200, page.statusCode());
        assertEquals("<html>ok</html>", page.html());
    }

    @Test
    void returnsNullForClientError() {
        assertNull(downloader.download(task("/missing")));
    }

    @Test
    void returnsNullForServerError() {
        assertNull(downloader.download(task("/error")));
    }

    @Test
    void followsRedirectToSuccessfulPage() {
        DownloadedPage page = downloader.download(task("/redirect"));
        assertNotNull(page);
        assertEquals(200, page.statusCode());
        assertTrue(page.uri().getPath().endsWith("/ok"));
    }

    @Test
    void stopsAfterMaxRedirects() {
        assertNull(downloader.download(task("/redirect-loop")));
    }

    @Test
    void rejectsRedirectToInternalMetadata() {
        assertNull(downloader.download(task("/redirect-to-internal")));
    }

    @Test
    void rejectsDecompressionBomb() {
        assertNull(downloader.download(task("/gzip-bomb")));
    }

    // --- SSRF policy unit tests -------------------------------------------------

    @Test
    void rejectsLoopbackAndPrivateHostsByIpLiteral() {
        assertRejected("http://127.0.0.1/x");
        assertRejected("http://0.0.0.0/x");
        assertRejected("http://10.0.0.1/x");
        assertRejected("http://172.16.0.1/x");
        assertRejected("http://192.168.1.1/x");
        assertRejected("http://169.254.169.254/latest/meta-data/");
    }

    @Test
    void rejectsIpv6InternalRanges() {
        assertRejected("http://[::1]/x");
        assertRejected("http://[fe80::1]/x");
        assertRejected("http://[fc00::1]/x");
        assertRejected("http://[ff02::1]/x");
    }

    @Test
    void rejectsUnsupportedSchemes() {
        assertRejected("ftp://example.com/x");
        assertRejected("file:///etc/passwd");
        assertRejected("javascript://example.com");
    }

    @Test
    void rejectsHostnameResolvingToPrivateAddress() {
        NetworkSafetyPolicy policy = new NetworkSafetyPolicy(true);
        NetworkSafetyPolicy.HostResolver internalResolver = host -> new java.net.InetAddress[]{ resolve("10.0.0.5") };
        NetworkSafetyPolicy dnsRebindingPolicy = new NetworkSafetyPolicy(internalResolver, false);
        assertRejected("http://internal.example/x", dnsRebindingPolicy);
        assertTrue(policy.isSafe(URI.create("http://localhost/x")));
    }

    private void assertRejected(String url) {
        assertRejected(url, new NetworkSafetyPolicy());
    }

    private void assertRejected(String url, NetworkSafetyPolicy policy) {
        HttpDownloader strictDownloader = new HttpDownloader(policy);
        UrlTask t = new UrlTask(url, "test", 0, Instant.now());
        assertNull(strictDownloader.download(t), "Expected " + url + " to be rejected");
    }

    private static java.net.InetAddress resolve(String ip) {
        try {
            return java.net.InetAddress.getByName(ip);
        } catch (java.net.UnknownHostException e) {
            throw new AssertionError(e);
        }
    }
}
