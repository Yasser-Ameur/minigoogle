package com.minigoogle.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.routing.BroadcastQueryRouter;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.cluster.transport.http.HttpSearchTransport;
import com.minigoogle.distributed.query.coordinator.DistributedSearchCoordinator;
import com.minigoogle.distributed.query.execution.LocalSearchExecutor;
import com.minigoogle.distributed.query.execution.SearchExecutor;
import com.minigoogle.network.dto.SearchResponse;
import com.minigoogle.network.dto.SearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterNodeIntegrationTest {

    private static final long CONVERGENCE_DEADLINE_MS = 8000;

    private ClusterNode node1;
    private ClusterNode node2;
    private ClusterNode node3;
    private NodeDirectory directory;

    @BeforeEach
    void setUp() throws IOException {
        // Fast gossip for tests
        long gossipInterval = 100;
        long timeout = 500;

        Map<String, Integer> portMap = new ConcurrentHashMap<>();
        portMap.put("node-1", 9091);
        portMap.put("node-2", 9092);
        portMap.put("node-3", 9093);

        directory = nodeId -> {
            Integer port = portMap.get(nodeId);
            return port != null ? URI.create("http://127.0.0.1:" + port) : null;
        };

        SearchExecutor node2Search = new LocalSearchExecutor(2, (query, topK) -> List.of(
                new SearchResult("http://node-2/result", "Node 2: " + query, "remote shard", 0.85, 0.7, 0.15)));

        node1 = new ClusterNode("node-1", 9091, directory, gossipInterval, timeout);
        node2 = new ClusterNode("node-2", 9092, directory, gossipInterval, timeout, node2Search);
        node3 = new ClusterNode("node-3", 9093, directory, gossipInterval, timeout);

        // Pre-populate some seed knowledge to bootstrap gossip without a proper discovery layer yet
        // In real life, seed nodes are injected at startup
        node1.getGossip().seedPeer("node-2");
        node2.getGossip().seedPeer("node-3");

        node1.start();
        node2.start();
        node3.start();
    }

    @AfterEach
    void tearDown() {
        if (node1 != null) node1.stop();
        if (node2 != null) node2.stop();
        if (node3 != null) node3.stop();
    }

    /**
     * Waits until the condition holds or the deadline passes.
     * Polling (rather than a fixed sleep) asserts the real property —
     * eventual convergence — without being brittle to gossip's randomized
     * single-peer selection and failure-detection timing.
     */
    private boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    private boolean allLiveSetsConverged() {
        Set<String> expected = Set.of("node-1", "node-2", "node-3");
        return Set.copyOf(node1.getGossip().getLiveNodes()).equals(expected)
                && Set.copyOf(node2.getGossip().getLiveNodes()).equals(expected)
                && Set.copyOf(node3.getGossip().getLiveNodes()).equals(expected);
    }

    @Test
    void testGossipDiscovery() throws InterruptedException {
        // Wait for gossip to propagate and converge
        assertTrue(waitUntil(this::allLiveSetsConverged, CONVERGENCE_DEADLINE_MS),
                "Gossip did not converge. node-1=" + node1.getGossip().getLiveNodes()
                        + " node-2=" + node2.getGossip().getLiveNodes()
                        + " node-3=" + node3.getGossip().getLiveNodes());
    }

    @Test
    void testRingIntegrationWithGossip() throws InterruptedException {
        // Wait for gossip to propagate and converge
        assertTrue(waitUntil(() -> node1.getRing().nodeCount() == 3
                && node2.getRing().nodeCount() == 3
                && node3.getRing().nodeCount() == 3, CONVERGENCE_DEADLINE_MS),
                "Rings did not converge. node-1=" + node1.getRing().nodeCount()
                        + " node-2=" + node2.getRing().nodeCount()
                        + " node-3=" + node3.getRing().nodeCount());

        // Verify that key routing works — any key should resolve to a valid node
        String owner = node1.getRing().getNode("test-document-123");
        assertTrue(owner.equals("node-1") || owner.equals("node-2") || owner.equals("node-3"),
                "Key should route to a valid cluster node");
    }

    @Test
    void testDistributedSearchFansOutToRemoteNodeOverTransport() throws InterruptedException {
        // Wait for gossip to converge so the broadcast router sees all nodes
        assertTrue(waitUntil(this::allLiveSetsConverged, CONVERGENCE_DEADLINE_MS),
                "Gossip did not converge: " + node1.getGossip().getLiveNodes());

        ObjectMapper mapper = new ObjectMapper();
        HttpSearchTransport transport = new HttpSearchTransport(directory, mapper, "node-1");

        List<LocalSearchExecutor> localExecutors = List.of(new LocalSearchExecutor(1, (query, topK) -> List.of(
                new SearchResult("http://node-1/result", "Node 1: " + query, "local shard", 0.9, 0.8, 0.1))));

        DistributedSearchCoordinator coordinator = new DistributedSearchCoordinator(
                new BroadcastQueryRouter(node1.getGossip()),
                transport,
                localExecutors,
                "node-1",
                4,
                Duration.ofSeconds(5),
                100);

        SearchResponse response = coordinator.search("distributed systems", 10);

        // node-1 resolves locally, node-2 answers over real HTTP, node-3 (no local
        // search) returns 503 and is skipped by the executor.
        assertEquals(2, response.results().size(), "Expected local + remote results: " + response.results());
        List<String> urls = response.results().stream().map(SearchResult::url).toList();
        assertTrue(urls.contains("http://node-1/result"));
        assertTrue(urls.contains("http://node-2/result"));
        coordinator.shutdown();
    }
}
