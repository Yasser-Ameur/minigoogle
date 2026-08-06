package com.minigoogle.cluster.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.RaftConsensus;
import com.minigoogle.cluster.transport.dto.RequestVoteRequest;
import com.minigoogle.cluster.transport.dto.RequestVoteResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RaftHandlerTest {
    private HttpServer server;
    private int port;
    private ObjectMapper mapper;
    private HttpClient client;
    private RaftConsensus raft;

    @BeforeEach
    void setUp() throws IOException {
        mapper = new ObjectMapper();
        raft = new RaftConsensus("local-node");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/cluster/v1/raft/request-vote", new RaftHandler(raft, mapper, "local-node"));
        server.createContext("/cluster/v1/raft/append-entries", new RaftHandler(raft, mapper, "local-node"));
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpResponse<String> post(String path, Object req) throws Exception {
        String payload = mapper.writeValueAsString(req);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void testRequestVoteEchoesMetadata() throws Exception {
        RequestVoteRequest req = new RequestVoteRequest(
                1, "vote-req-1", "vote-corr-1", "candidate-1", 0L, "candidate-1", 3, 10, 4);

        HttpResponse<String> resp = post("/cluster/v1/raft/request-vote", req);

        assertEquals(200, resp.statusCode());
        RequestVoteResponse body = mapper.readValue(resp.body(), RequestVoteResponse.class);
        assertEquals("vote-req-1", body.requestId());
        assertEquals("vote-corr-1", body.correlationId());
        assertEquals("local-node", body.sourceNodeId());
        assertTrue(body.voteGranted());
    }

    @Test
    void testRequestVoteWithHigherTermWins() throws Exception {
        RequestVoteRequest req = new RequestVoteRequest(
                1, "vote-req-2", "vote-corr-2", "candidate-2", 0L, "candidate-2", 5, 10, 4);

        HttpResponse<String> resp = post("/cluster/v1/raft/request-vote", req);

        RequestVoteResponse body = mapper.readValue(resp.body(), RequestVoteResponse.class);
        assertTrue(body.voteGranted());
        assertEquals(5, body.term());
    }

    @Test
    void testUnsupportedVersionRejected() throws Exception {
        RequestVoteRequest req = new RequestVoteRequest(
                999, "vote-req-3", "vote-corr-3", "candidate-3", 0L, "candidate-3", 3, 10, 4);

        HttpResponse<String> resp = post("/cluster/v1/raft/request-vote", req);

        assertEquals(400, resp.statusCode());
    }

    @Test
    void testAppendEntriesEchoesMetadata() throws Exception {
        com.minigoogle.cluster.transport.dto.AppendEntriesRequest req =
                new com.minigoogle.cluster.transport.dto.AppendEntriesRequest(
                        1, "ae-req-1", "ae-corr-1", "leader-1", 0L, "leader-1", 2, 1, 1, java.util.List.of(), 1);

        HttpResponse<String> resp = post("/cluster/v1/raft/append-entries", req);

        assertEquals(200, resp.statusCode());
        com.minigoogle.cluster.transport.dto.AppendEntriesResponse body =
                mapper.readValue(resp.body(), com.minigoogle.cluster.transport.dto.AppendEntriesResponse.class);
        assertEquals("ae-req-1", body.requestId());
        assertEquals("ae-corr-1", body.correlationId());
        assertEquals("local-node", body.sourceNodeId());
        assertTrue(body.success());
    }
}
