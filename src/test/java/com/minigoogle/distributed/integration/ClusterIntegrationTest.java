package com.minigoogle.distributed.integration;

import com.google.gson.Gson;
import com.minigoogle.network.dto.SearchResult;
import com.minigoogle.network.http.RestClient;
import com.minigoogle.network.http.RestServer;
import com.minigoogle.network.serialization.JsonSerializer;
import com.minigoogle.distributed.coordinator.ClusterCoordinator;
import com.minigoogle.distributed.coordinator.SearchCoordinator;
import com.minigoogle.distributed.model.NodeInfo;
import com.minigoogle.distributed.model.NodeRole;
import com.minigoogle.distributed.model.NodeStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the end-to-end distributed scatter-gather search path over real HTTP. */
class ClusterIntegrationTest {

    private ClusterCoordinator clusterCoordinator;
    private RestServer searchNodeA;
    private RestServer searchNodeB;
    private final RestClient client = new RestClient();
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        // Start coordinator on an ephemeral port
        clusterCoordinator = new ClusterCoordinator(0);
        clusterCoordinator.start();

        // Two mock search nodes that answer the standard /api/v1/search protocol
        searchNodeA = mockSearchNode(0.9, "http://a.example.com/1", "A1",
                0.7, "http://a.example.com/2", "A2");
        searchNodeB = mockSearchNode(0.95, "http://b.example.com/1", "B1",
                0.5, "http://b.example.com/2", "B2");
    }

    private RestServer mockSearchNode(double score1, String url1, String title1,
                                      double score2, String url2, String title2) {
        RestServer node = new RestServer(0);
        node.post("/api/v1/search", body -> {
            assertTrue(body.contains("query"), "Search request body should carry the query");
            List<SearchResult> results = List.of(
                    new SearchResult(url1, title1, "snippet", score1, 0.0, 0.0),
                    new SearchResult(url2, title2, "snippet", score2, 0.0, 0.0));
            return JsonSerializer.toJson(new com.minigoogle.network.dto.SearchResponse(1, results.size(), results));
        });
        node.start();
        return node;
    }

    private void registerNode(String nodeId, RestServer node) {
        NodeInfo info = new NodeInfo(nodeId, "localhost", node.getPort(), NodeRole.INDEX,
                NodeStatus.ONLINE, System.currentTimeMillis());
        client.post("http://localhost:" + clusterCoordinator.getPort() + "/register", gson.toJson(info));
    }

    @AfterEach
    void tearDown() {
        if (searchNodeA != null) searchNodeA.stop();
        if (searchNodeB != null) searchNodeB.stop();
        if (clusterCoordinator != null) clusterCoordinator.stop();
    }

    @Test
    void testNodesRegisterAndReceiveAutoShards() {
        registerNode("index-a", searchNodeA);
        registerNode("index-b", searchNodeB);

        String stateJson = client.get("http://localhost:" + clusterCoordinator.getPort() + "/state");
        var state = JsonSerializer.fromJson(stateJson, com.minigoogle.distributed.registry.ClusterState.class);

        assertEquals(2, state.nodes().size(), "Both index nodes should be registered");
        assertEquals(2, state.shards().size(), "Each index node should own a shard");
        assertTrue(stateJson.contains("index-a"));
        assertTrue(stateJson.contains("index-b"));
        assertTrue(stateJson.contains("ONLINE"));
    }

    @Test
    void testDistributedSearchScatterGather() {
        registerNode("index-a", searchNodeA);
        registerNode("index-b", searchNodeB);

        SearchCoordinator coordinator = new SearchCoordinator("http://localhost:" + clusterCoordinator.getPort());
        List<SearchResult> results = coordinator.search("test", 3);

        assertEquals(3, results.size(), "Global top-K should merge both shards");
        // Sorted by decreasing score
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).score() >= results.get(i).score(),
                    "Merged results should be sorted by decreasing score");
        }
        assertTrue(results.stream().anyMatch(r -> r.url().startsWith("http://a.example.com/")),
                "Results should include node A");
        assertTrue(results.stream().anyMatch(r -> r.url().startsWith("http://b.example.com/")),
                "Results should include node B");
    }

    @Test
    void testSearchWithNoRegisteredNodesReturnsEmpty() {
        SearchCoordinator coordinator = new SearchCoordinator("http://localhost:" + clusterCoordinator.getPort());
        assertTrue(coordinator.search("test", 3).isEmpty());
    }
}
