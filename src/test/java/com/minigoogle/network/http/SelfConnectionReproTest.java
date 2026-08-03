package com.minigoogle.network.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Reproduces the coordinator self-connection issue: HTTP GET to a RestServer in the same JVM. */
class SelfConnectionReproTest {

    @Test
    void sameJvmHttpClientCanReachLocalRestServer() {
        RestServer server = new RestServer(0);
        server.get("/state", body -> "{\"ok\":true}");
        server.start();
        try {
            int port = server.getPort();
            System.out.println("server on port " + port);
            RestClient client = new RestClient();
            String body = client.get("http://localhost:" + port + "/state");
            System.out.println("GET body = " + body);
            assertTrue(body.contains("ok"));
        } catch (Exception e) {
            e.printStackTrace();
            fail("self-connection failed: " + e);
        } finally {
            server.stop();
        }
    }
}
