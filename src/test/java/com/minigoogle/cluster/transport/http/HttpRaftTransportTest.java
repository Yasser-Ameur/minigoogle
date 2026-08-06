package com.minigoogle.cluster.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.cluster.transport.dto.RequestVoteRequest;
import com.minigoogle.cluster.transport.dto.RequestVoteResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HttpRaftTransportTest {
    private HttpServer server;
    private int port;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
        mapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private RequestVoteRequest readRequest(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return mapper.readValue(is, RequestVoteRequest.class);
        }
    }

    private void writeResponse(HttpExchange exchange, RequestVoteResponse resp) throws IOException {
        String json = mapper.writeValueAsString(resp);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, json.length());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void testSendRequestVote_Success() throws Exception {
        server.createContext("/cluster/v1/raft/request-vote", exchange -> {
            RequestVoteRequest req = readRequest(exchange);
            RequestVoteResponse resp = new RequestVoteResponse(
                    req.protocolVersion(),
                    req.requestId(),
                    req.correlationId(),
                    "node-2",
                    System.currentTimeMillis(),
                    5,
                    true
            );
            writeResponse(exchange, resp);
        });

        NodeDirectory dir = nodeId -> URI.create("http://localhost:" + port);
        HttpRaftTransport transport = new HttpRaftTransport(dir, mapper, "node-1");

        RequestVoteRequest req = new RequestVoteRequest(1, "req-1", "corr-1", "node-1", 0L, "node-1", 5, 10, 4);
        RequestVoteResponse response = transport.sendRequestVote("node-2", req)
                .get(2, TimeUnit.SECONDS);

        assertNotNull(response);
        assertTrue(response.voteGranted());
        assertEquals(5, response.term());
        assertEquals(1, response.protocolVersion());
    }

    @Test
    void testSendRequestVote_ErrorResponse() {
        server.createContext("/cluster/v1/raft/request-vote", exchange -> {
            exchange.sendResponseHeaders(500, -1);
        });

        NodeDirectory dir = nodeId -> URI.create("http://localhost:" + port);
        HttpRaftTransport transport = new HttpRaftTransport(dir, mapper, "node-1");

        RequestVoteRequest req = new RequestVoteRequest(1, "req-1", "corr-1", "node-1", 0L, "node-1", 5, 10, 4);
        var future = transport.sendRequestVote("node-2", req);

        Exception ex = assertThrows(Exception.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("HTTP Error"));
    }

    @Test
    void testSendRequestVote_MismatchedCorrelationIdIsRejected() {
        server.createContext("/cluster/v1/raft/request-vote", exchange -> {
            RequestVoteRequest req = readRequest(exchange);
            // Malicious/buggy peer echoes a different correlation ID
            RequestVoteResponse resp = new RequestVoteResponse(
                    req.protocolVersion(),
                    req.requestId(),
                    "different-correlation",
                    "node-2",
                    System.currentTimeMillis(),
                    5,
                    true
            );
            writeResponse(exchange, resp);
        });

        NodeDirectory dir = nodeId -> URI.create("http://localhost:" + port);
        HttpRaftTransport transport = new HttpRaftTransport(dir, mapper, "node-1");

        RequestVoteRequest req = new RequestVoteRequest(1, "req-1", "corr-1", "node-1", 0L, "node-1", 5, 10, 4);
        var future = transport.sendRequestVote("node-2", req);

        Exception ex = assertThrows(Exception.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof com.minigoogle.cluster.transport.ProtocolViolationException,
                "Expected a ProtocolViolationException wrapping correlation mismatch");
    }
}
