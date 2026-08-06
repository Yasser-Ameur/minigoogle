package com.minigoogle.cluster.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.GossipProtocol;
import com.minigoogle.cluster.GossipProtocol.GossipNodeState;
import com.minigoogle.cluster.GossipProtocol.NodeStatus;
import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.dto.GossipExchangeRequest;
import com.minigoogle.cluster.transport.dto.GossipExchangeResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GossipHandlerTest {
    private HttpServer server;
    private int port;
    private ObjectMapper mapper;
    private HttpClient client;
    private GossipProtocol gossip;

    @BeforeEach
    void setUp() throws IOException {
        mapper = new ObjectMapper();
        gossip = new GossipProtocol("local-node");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/cluster/v1/gossip/exchange", new GossipHandler(gossip, mapper, "local-node"));
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpResponse<String> post(GossipExchangeRequest req) throws Exception {
        String payload = mapper.writeValueAsString(req);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + port + "/cluster/v1/gossip/exchange"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void testEchoesCorrelationAndRequestIds() throws Exception {
        GossipNodeState peerState = new GossipNodeState("peer-1", 3, NodeStatus.ALIVE, System.currentTimeMillis());
        GossipExchangeRequest req = new GossipExchangeRequest(
                1, "req-123", "corr-456", "peer-1", 0L, Map.of("peer-1", peerState));

        HttpResponse<String> resp = post(req);

        assertEquals(200, resp.statusCode());
        GossipExchangeResponse body = mapper.readValue(resp.body(), GossipExchangeResponse.class);
        assertEquals("req-123", body.requestId());
        assertEquals("corr-456", body.correlationId());
        assertEquals("local-node", body.sourceNodeId());
        assertTrue(body.accepted());
    }

    @Test
    void testReceivedGossipMergesMembership() throws Exception {
        GossipNodeState peerState = new GossipNodeState("peer-1", 7, NodeStatus.ALIVE, System.currentTimeMillis());
        GossipExchangeRequest req = new GossipExchangeRequest(
                1, "req-1", "corr-1", "peer-1", 0L, Map.of("peer-1", peerState));

        post(req);

        assertTrue(gossip.getMembershipTable().containsKey("peer-1"));
        assertTrue(gossip.getLiveNodes().contains("peer-1"));
    }

    @Test
    void testUnsupportedVersionRejected() throws Exception {
        GossipExchangeRequest req = new GossipExchangeRequest(
                999, "req-1", "corr-1", "peer-1", 0L, Map.of());

        HttpResponse<String> resp = post(req);

        assertEquals(400, resp.statusCode());
    }

    @Test
    void testGetMethodNotAllowed() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + port + "/cluster/v1/gossip/exchange"))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resp.statusCode());
    }
}
