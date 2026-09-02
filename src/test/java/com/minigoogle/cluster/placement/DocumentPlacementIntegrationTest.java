package com.minigoogle.cluster.placement;

import com.minigoogle.cluster.ClusterNode;
import com.minigoogle.cluster.ClusterSecurity;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.distributed.query.execution.SearchExecutor;
import com.minigoogle.distributed.query.model.LocalSearchResponse;
import com.minigoogle.network.dto.SearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three real cluster nodes, real HTTP, in-memory document stores: exercises
 * {@link ClusterNode#place}, {@link PlacementRepairListener}, and {@link
 * ClusterNode#distributedSearch} together.
 */
class DocumentPlacementIntegrationTest {

    private static final long WAIT_DEADLINE_MS = 8000;

    /** An in-memory, idempotent-by-URL document store standing in for a real index. */
    private static final class FakeStore implements DocumentIngest, LocalDocuments {
        final Map<String, IngestedDocument> byUrl = new ConcurrentHashMap<>();

        @Override
        public boolean ingest(IngestedDocument doc) {
            return byUrl.putIfAbsent(doc.url().toString(), doc) == null;
        }

        @Override
        public Iterable<IngestedDocument> all() {
            return List.copyOf(byUrl.values());
        }
    }

    private ClusterNode node1;
    private ClusterNode node2;
    private ClusterNode node3;
    private FakeStore store1;
    private FakeStore store2;
    private FakeStore store3;
    private NodeDirectory directory;
    private ClusterSecurity security;

    @BeforeEach
    void setUp() throws IOException {
        Map<String, Integer> portMap = Map.of("node-1", 9401, "node-2", 9402, "node-3", 9403);
        directory = nodeId -> {
            Integer port = portMap.get(nodeId);
            return port != null ? URI.create("http://127.0.0.1:" + port) : null;
        };

        security = new ClusterSecurity("placement-integration-secret");
        store1 = new FakeStore();
        store2 = new FakeStore();
        store3 = new FakeStore();
    }

    @AfterEach
    void tearDown() {
        if (node1 != null) node1.stop();
        if (node2 != null) node2.stop();
        if (node3 != null) node3.stop();
    }

    private ClusterNode newNode(String nodeId, int port, SearchExecutor search, FakeStore store,
                                 int replicationFactor) throws IOException {
        return new ClusterNode(nodeId, port, directory, 100, 500, 400, 150, search, security,
                null, null, null, null, null, 0, null, replicationFactor, store, store);
    }

    private boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    private IngestedDocument document(String url) {
        return new IngestedDocument(UUID.randomUUID(), URI.create(url), "Title for " + url,
                "Body text for " + url, List.of(), Instant.now());
    }

    @Test
    void placeDeliversToEveryOwner() throws IOException, InterruptedException {
        // Full replication (factor 3 on a 3-node ring): every owner is every node.
        node1 = newNode("node-1", 9401, null, store1, 3);
        node2 = newNode("node-2", 9402, null, store2, 3);
        node3 = newNode("node-3", 9403, null, store3, 3);
        node1.start();
        node2.start();
        node3.start();

        // Bootstrap node-1's own view of membership directly, without depending
        // on gossip's own failure-detection/round timing.
        node1.getGossip().seedPeer("node-2");
        node1.getGossip().seedPeer("node-3");
        assertTrue(waitUntil(() -> node1.getRing().nodeCount() == 3),
                "node-1's ring did not pick up node-2 and node-3");

        IngestedDocument doc = document("http://example.com/placed-doc");
        store1.ingest(doc); // the crawling node always keeps its own local copy

        PlacementResult result = node1.place(doc);

        assertEquals(3, result.owners().size());
        assertTrue(result.owners().containsAll(List.of("node-1", "node-2", "node-3")));
        assertTrue(result.selfIsOwner());
        assertEquals(List.of(), result.failedTo());
        assertTrue(result.deliveredTo().containsAll(List.of("node-2", "node-3")));

        assertTrue(store2.byUrl.containsKey(doc.url().toString()), "node-2 should have received the document");
        assertTrue(store3.byUrl.containsKey(doc.url().toString()), "node-3 should have received the document");
    }

    @Test
    void repairDeliversToANewOwnerAfterMembershipChange() throws IOException, InterruptedException {
        // Full replication so a node joining the ring always becomes an owner.
        node1 = newNode("node-1", 9401, null, store1, 3);
        node2 = newNode("node-2", 9402, null, store2, 3);
        node3 = newNode("node-3", 9403, null, store3, 3);
        node1.start();
        node2.start();
        node3.start();

        // node-1 starts out only knowing about node-2; node-3 joins its view later.
        node1.getGossip().seedPeer("node-2");
        assertTrue(waitUntil(() -> node1.getRing().nodeCount() == 2),
                "node-1's ring did not pick up node-2");

        IngestedDocument doc = document("http://example.com/repaired-doc");
        store1.ingest(doc);
        PlacementResult initial = node1.place(doc);
        assertEquals(2, initial.owners().size());
        assertFalse(initial.owners().contains("node-3"));
        assertFalse(store3.byUrl.containsKey(doc.url().toString()));

        // Simulate node-3 joining node-1's view directly (no dependence on the
        // sibling's real failure-detection/gossip-round timing): this fires
        // PlacementRepairListener.onNodeJoined.
        node1.getGossip().seedPeer("node-3");

        assertTrue(waitUntil(() -> store3.byUrl.containsKey(doc.url().toString())),
                "repair did not replicate the document to the new owner within the deadline");

        // Nothing already-placed is ever deleted.
        assertTrue(store2.byUrl.containsKey(doc.url().toString()));
    }

    @Test
    void repairIsIdempotentOnANodeLeavingAndRejoining() throws IOException, InterruptedException {
        node1 = newNode("node-1", 9401, null, store1, 2);
        node2 = newNode("node-2", 9402, null, store2, 2);
        node1.start();
        node2.start();

        node1.getGossip().seedPeer("node-2");
        assertTrue(waitUntil(() -> node1.getRing().nodeCount() == 2));

        IngestedDocument doc = document("http://example.com/reconfirmed-doc");
        store1.ingest(doc);
        node1.place(doc);
        assertTrue(store2.byUrl.containsKey(doc.url().toString()));

        // A leave/rejoin cycle on node-1's view re-triggers repair; delivering
        // an already-present document again must not fail or duplicate it.
        node1.getGossip().confirmDead("node-2");
        node1.getGossip().seedPeer("node-2");

        assertTrue(waitUntil(() -> store2.byUrl.size() == 1));
        assertEquals(1, store2.byUrl.size());
    }

    @Test
    void distributedSearchMergesAndDedupesByUrlAcrossThreeNodes() throws IOException, InterruptedException {
        SearchExecutor search1 = context -> new LocalSearchResponse(1, List.of(
                new SearchResult("http://example.com/a", "A", "shared on node-1 and node-2", 0.95, 0.9, 0.1)), 1, 1);
        SearchExecutor search2 = context -> new LocalSearchResponse(2, List.of(
                new SearchResult("http://example.com/a", "A (replica)", "shared on node-1 and node-2", 0.80, 0.7, 0.1),
                new SearchResult("http://example.com/b", "B", "only on node-2", 0.70, 0.6, 0.1)), 2, 1);
        SearchExecutor search3 = context -> new LocalSearchResponse(3, List.of(
                new SearchResult("http://example.com/c", "C", "only on node-3", 0.60, 0.5, 0.1)), 1, 1);

        node1 = newNode("node-1", 9401, search1, store1, 2);
        node2 = newNode("node-2", 9402, search2, store2, 2);
        node3 = newNode("node-3", 9403, search3, store3, 2);
        node1.start();
        node2.start();
        node3.start();

        node1.getGossip().seedPeer("node-2");
        node1.getGossip().seedPeer("node-3");

        List<SearchResult> results = node1.distributedSearch("shared query", 10);

        assertEquals(3, results.size(), "Expected one merged result per distinct URL: " + results);
        List<String> urls = results.stream().map(SearchResult::url).toList();
        assertTrue(urls.contains("http://example.com/a"));
        assertTrue(urls.contains("http://example.com/b"));
        assertTrue(urls.contains("http://example.com/c"));
        // The higher-scoring copy of the duplicated URL is the one that should win.
        SearchResult merged = results.stream().filter(r -> r.url().equals("http://example.com/a")).findFirst().orElseThrow();
        assertEquals("A", merged.title());
    }
}
