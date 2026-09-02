package com.minigoogle.cluster.transport.http;

import com.minigoogle.cluster.ClusterSecurity;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BodyLimitFilterTest {
    private static final long CAP = 64;

    private HttpServer server;
    private int port;
    private String token;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        ClusterSecurity security = new ClusterSecurity("test-secret");
        token = security.deriveToken("peer");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        HttpContext context = server.createContext("/cluster/v1/echo", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        context.getFilters().add(new AuthFilter(security));
        context.getFilters().add(new BodyLimitFilter(CAP));
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private HttpResponse<String> post(HttpRequest.BodyPublisher body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/cluster/v1/echo"))
                .header(HttpAuth.AUTHORIZATION, "Bearer " + token)
                .header(HttpAuth.NODE_ID, "peer")
                .header("Content-Type", "application/json")
                .POST(body)
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bodyWithinTheCapPasses() throws Exception {
        HttpResponse<String> response = post(HttpRequest.BodyPublishers.ofString("{\"ok\":true}"));
        assertEquals(200, response.statusCode());
        assertEquals("{\"ok\":true}", response.body());
    }

    @Test
    void bodyAboveTheCapIsRefusedBeforeItIsRead() throws Exception {
        String big = "x".repeat((int) CAP + 1);
        HttpResponse<String> response = post(HttpRequest.BodyPublishers.ofString(big));
        assertEquals(413, response.statusCode());
    }

    @Test
    void bodyWithoutADeclaredLengthIsRefused() throws Exception {
        // A streaming publisher of unknown length is sent chunked, so no Content-Length header.
        HttpRequest.BodyPublisher chunked = HttpRequest.BodyPublishers.ofInputStream(
                () -> new java.io.ByteArrayInputStream("{}".getBytes()));
        HttpResponse<String> response = post(chunked);
        assertEquals(411, response.statusCode());
    }
}
