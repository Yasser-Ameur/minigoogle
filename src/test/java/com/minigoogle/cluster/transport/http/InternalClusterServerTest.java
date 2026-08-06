package com.minigoogle.cluster.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

class InternalClusterServerTest {

    @Test
    void testStartServesRequests() throws Exception {
        InternalClusterServer server = new InternalClusterServer(0, new ObjectMapper());
        server.getServer().createContext("/cluster/v1/test", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        server.start();
        try {
            int port = server.getServer().getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/cluster/v1/test"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void testDoubleStartThrows() throws IOException {
        InternalClusterServer server = new InternalClusterServer(0, new ObjectMapper());
        server.start();
        try {
            assertThrows(IllegalStateException.class, server::start);
        } finally {
            server.stop();
        }
    }

    @Test
    void testStopClosesListener() throws IOException {
        InternalClusterServer server = new InternalClusterServer(0, new ObjectMapper());
        server.start();
        int port = server.getServer().getAddress().getPort();
        server.stop();

        HttpClient client = HttpClient.newHttpClient();
        assertThrows(Exception.class, () -> client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/cluster/v1/test"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString()));
    }
}
