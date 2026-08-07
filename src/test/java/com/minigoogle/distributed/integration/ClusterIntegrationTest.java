package com.minigoogle.distributed.integration;

import com.google.gson.Gson;
import com.minigoogle.ml.features.RawFeatures;
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
    private RestServer featureNodeA;
    private RestServer featureNodeB;
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

    private RestServer mockFeatureSearchNode(List<SearchResult> results,
                                             double maxPageRank, double maxDocLength) {
        RestServer node = new RestServer(0);
        node.post("/api/v1/search", body -> {
            assertTrue(body.contains("query"), "Search request body should carry the query");
            return JsonSerializer.toJson(new com.minigoogle.network.dto.SearchResponse(
                    1, results.size(), results, null, maxPageRank, maxDocLength));
        });
        node.start();
        return node;
    }

    private static SearchResult featureResult(String url, double retrievalScore,
                                              RawFeatures raw) {
        return new SearchResult(url, url, "snippet", retrievalScore,
                raw.bm25(), raw.pageRank(), raw.toArray());
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
        if (featureNodeA != null) featureNodeA.stop();
        if (featureNodeB != null) featureNodeB.stop();
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

    @Test
    void testDistributedGlobalRankingUsesRawFeatures() {
        // Shard A: strong lexical match, weak authority; served with a modest
        // retrieval score.
        RawFeatures a = new RawFeatures(0.9, 0.1, 0.5, 0.0, 0.9, 0.0, 100, 0.0);
        // Shard B: weak lexical match, strong authority; served with a HIGHER
        // retrieval score so a score-only merge would put it first.
        RawFeatures b = new RawFeatures(0.2, 0.9, 0.1, 0.0, 0.2, 0.0, 100, 0.0);

        featureNodeA = mockFeatureSearchNode(
                List.of(featureResult("http://a.example.com/lexical", 0.8, a)), 1.0, 200);
        featureNodeB = mockFeatureSearchNode(
                List.of(featureResult("http://b.example.com/authority", 0.9, b)), 2.0, 200);
        registerNode("index-a", featureNodeA);
        registerNode("index-b", featureNodeB);

        SearchCoordinator coordinator = new SearchCoordinator("http://localhost:" + clusterCoordinator.getPort());
        List<SearchResult> results = coordinator.search("test", 10);

        assertEquals(2, results.size(), "Both shard candidates should be merged");
        assertEquals("http://a.example.com/lexical", results.get(0).url(),
                "Global ranking must use raw features: the BM25-heavy doc outranks "
                        + "the authority-heavy doc despite its lower retrieval score");
        assertEquals("http://b.example.com/authority", results.get(1).url());
    }

    @Test
    void testDistributedGlobalRankingDeduplicatesByUrl() {
        RawFeatures a = new RawFeatures(0.8, 0.2, 0.6, 0.0, 0.8, 0.0, 100, 0.0);
        RawFeatures b = new RawFeatures(0.5, 0.5, 0.2, 0.0, 0.5, 0.0, 100, 0.0);

        featureNodeA = mockFeatureSearchNode(
                List.of(featureResult("http://a.example.com/shared", 0.9, a)), 1.0, 200);
        featureNodeB = mockFeatureSearchNode(
                List.of(featureResult("http://a.example.com/shared", 0.6, b),
                        featureResult("http://b.example.com/unique", 0.4, b)), 1.0, 200);
        registerNode("index-a", featureNodeA);
        registerNode("index-b", featureNodeB);

        SearchCoordinator coordinator = new SearchCoordinator("http://localhost:" + clusterCoordinator.getPort());
        List<SearchResult> results = coordinator.search("test", 10);

        assertEquals(2, results.size(),
                "The URL served by both shards must be deduplicated");
        assertEquals(2, results.stream().map(SearchResult::url).distinct().count());
        assertTrue(results.stream().anyMatch(r -> r.url().equals("http://b.example.com/unique")));
    }

    @Test
    void testCoordinatorTrainsRankingModelOnClicksFromServedImpressions() {
        // Shard A serves a strong lexical result; shard B serves a strong
        // authority result. With a fresh model the lexical doc wins (BM25 is
        // weighted higher in the default model).
        RawFeatures lexical = new RawFeatures(0.9, 0.1, 0.5, 0.0, 0.9, 0.0, 100, 0.0);
        RawFeatures authority = new RawFeatures(0.2, 0.9, 0.1, 0.0, 0.2, 0.0, 100, 0.0);

        featureNodeA = mockFeatureSearchNode(
                List.of(featureResult("http://a.example.com/lexical", 0.8, lexical)), 1.0, 200);
        featureNodeB = mockFeatureSearchNode(
                List.of(featureResult("http://b.example.com/authority", 0.9, authority)), 1.0, 200);
        registerNode("index-a", featureNodeA);
        registerNode("index-b", featureNodeB);

        // trainAfterClicks = 1 so the first click triggers a training pass.
        SearchCoordinator coordinator = new SearchCoordinator(
                "http://localhost:" + clusterCoordinator.getPort(), 3, 1, 3, 0.05);

        List<SearchResult> served = coordinator.search("test", 10);
        assertEquals(2, served.size());
        assertEquals("http://a.example.com/lexical", served.get(0).url());
        assertEquals(2, coordinator.impressionCount(), "Every served result counts as an impression");

        double[] before = coordinator.modelWeights().clone();
        // The user clicks the second-ranked authority document: it should be
        // preferred over the lexical doc above it.
        int trainedPairs = coordinator.recordClick(
                "test", "http://b.example.com/authority", 2, "session-1");

        assertEquals(1, coordinator.clickCount());
        assertTrue(trainedPairs > 0, "A click below position 1 must produce a preference pair");
        assertFalse(java.util.Arrays.equals(before, coordinator.modelWeights()),
                "Click training must update the coordinator's shared ranking model");
    }
}
