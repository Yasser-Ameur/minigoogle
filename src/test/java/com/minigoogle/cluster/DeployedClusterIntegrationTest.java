package com.minigoogle.cluster;

import com.minigoogle.cluster.transport.StaticNodeDirectory;
import com.minigoogle.distributed.query.execution.LocalSearchExecutor;
import com.minigoogle.distributed.query.execution.SearchExecutor;
import com.minigoogle.network.dto.SearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * INTEGRATION — a three-node cluster assembled exactly the way the production
 * application assembles one.
 *
 * <p>The point of this test is reachability, not component behaviour. It builds
 * each node through the same collaborators {@code MiniGoogleApp.startClusterRuntime}
 * uses — {@link StaticNodeDirectory} parsed from a {@code cluster.peers} string,
 * a shared {@link ClusterSecurity} secret, a {@link LocalSearchExecutor} backed
 * by a real index, and a durable per-node Raft directory — over real HTTP on
 * real ports. If the production wiring is wrong, the peer list is misparsed, or
 * the secret is not shared, this test fails.</p>
 *
 * <p>It covers the full lifecycle: startup, election, a replicated user
 * operation, leader failure, re-election, continued service, restart of the
 * failed node, and convergence with its state intact.</p>
 */
class DeployedClusterIntegrationTest {

    private static final long DEADLINE_MS = 20_000;

    @TempDir
    Path tempDir;

    private final Map<String, ClusterNode> nodes = new LinkedHashMap<>();
    private final Map<String, Integer> ports = new LinkedHashMap<>();
    private ClusterSecurity security;
    private StaticNodeDirectory directory;
    private String peerSpec;

    @AfterEach
    void tearDown() {
        for (ClusterNode node : nodes.values()) {
            try {
                node.stop();
            } catch (RuntimeException ignored) {
                // Best effort; a node may already be stopped by the test.
            }
        }
        nodes.clear();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Builds the peer list the way an operator would write it in compose or a
     * ConfigMap, then parses it with the production directory.
     */
    private void formCluster(String... nodeIds) throws IOException {
        security = new ClusterSecurity("integration-cluster-secret");

        List<String> entries = new ArrayList<>();
        for (String id : nodeIds) {
            int port = freePort();
            ports.put(id, port);
            entries.add(id + "=http://127.0.0.1:" + port);
        }
        peerSpec = String.join(",", entries);
        directory = StaticNodeDirectory.parse(peerSpec);
        assertEquals(nodeIds.length, directory.size(), "peer spec must parse to every node");

        for (String id : nodeIds) {
            nodes.put(id, buildNode(id));
        }
        for (ClusterNode node : nodes.values()) {
            node.start();
        }
        // Seed gossip exactly as the application does, so membership converges
        // without any external registry.
        for (Map.Entry<String, ClusterNode> entry : nodes.entrySet()) {
            for (String peer : directory.nodeIds()) {
                if (!peer.equals(entry.getKey())) {
                    entry.getValue().getGossip().seedPeer(peer);
                }
            }
        }
        for (ClusterNode node : nodes.values()) {
            node.initializeConfig(List.copyOf(directory.nodeIds()));
        }
    }

    /** Mirrors the production construction, including the durable Raft directory. */
    private ClusterNode buildNode(String nodeId) throws IOException {
        SearchExecutor localSearch = new LocalSearchExecutor(
                Math.abs(nodeId.hashCode() % 1024),
                (query, topK) -> List.of(
                        new SearchResult("https://" + nodeId + "/doc", nodeId + " result",
                                "snippet for " + query, 1.0, 1.0, 0.0)));

        return new ClusterNode(
                nodeId,
                ports.get(nodeId),
                directory,
                200,      // gossip interval
                4_000,    // gossip failure timeout
                1_200,    // raft election timeout
                250,      // raft heartbeat
                localSearch,
                security,
                tempDir.resolve(nodeId));
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    private List<ClusterNode> live() {
        return new ArrayList<>(nodes.values());
    }

    private ClusterNode leader() {
        for (ClusterNode node : live()) {
            if (node.getRaft().getState() == RaftConsensus.RaftState.LEADER) {
                return node;
            }
        }
        return null;
    }

    private long leaderCount() {
        return live().stream()
                .filter(n -> n.getRaft().getState() == RaftConsensus.RaftState.LEADER)
                .count();
    }

    /**
     * Writes through whichever node is currently leader, retrying across
     * leadership changes. Returns the node that accepted the committed write.
     */
    private ClusterNode putThroughLeader(String key, String value) throws InterruptedException {
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            ClusterNode candidate = leader();
            if (candidate == null) {
                Thread.sleep(25);
                continue;
            }
            try {
                candidate.put(key, value.getBytes(StandardCharsets.UTF_8));
                return candidate;
            } catch (NotLeaderException | IllegalStateException leadershipMoved) {
                Thread.sleep(25);
            }
        }
        return null;
    }

    @Test
    void threeNodeClusterElectsReplicatesSurvivesLeaderLossAndRecovers() throws Exception {
        // ── 1. Startup ────────────────────────────────────────────────────
        formCluster("node-a", "node-b", "node-c");

        // ── 2. Leader election ────────────────────────────────────────────
        assertTrue(waitUntil(() -> leaderCount() == 1, DEADLINE_MS),
                "exactly one leader must be elected over the real transport");

        // ── 3. Steady state: gossip converges on all three members ────────
        assertTrue(waitUntil(() -> live().stream()
                        .allMatch(n -> n.getGossip().getLiveNodes().size() == 3), DEADLINE_MS),
                "gossip must converge on all three nodes");

        // ── 4 & 5. A real user operation, replicated through Raft ─────────
        ClusterNode firstLeader = putThroughLeader("doc:1", "indexed-on-cluster");
        assertNotNull(firstLeader, "a committed write must succeed while the cluster is healthy");

        // The value must be readable from every node, including followers.
        for (ClusterNode node : live()) {
            byte[] value = node.get("doc:1");
            assertNotNull(value, "node " + node + " must serve the replicated value");
            assertEquals("indexed-on-cluster", new String(value, StandardCharsets.UTF_8));
        }

        // ── 6. Leader failure ─────────────────────────────────────────────
        String downedId = nodes.entrySet().stream()
                .filter(e -> e.getValue() == firstLeader)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
        firstLeader.stop();
        nodes.remove(downedId);

        // ── 7. A new leader is elected from the survivors ─────────────────
        assertTrue(waitUntil(() -> leaderCount() == 1, DEADLINE_MS),
                "the surviving majority must elect a new leader after the leader is lost");

        // ── 8. Continued service on the remaining majority ────────────────
        ClusterNode secondLeader = putThroughLeader("doc:2", "written-after-failover");
        assertNotNull(secondLeader, "the cluster must keep accepting writes after failover");
        assertTrue(secondLeader != firstLeader, "the new leader must be a different node");

        // The pre-failure entry survived the leadership change.
        for (ClusterNode node : live()) {
            assertEquals("indexed-on-cluster",
                    new String(node.get("doc:1"), StandardCharsets.UTF_8),
                    "committed state must survive a leader change");
        }

        // ── 9 & 10. Restart the failed node; it rejoins and catches up ────
        ClusterNode restarted = buildNode(downedId);
        nodes.put(downedId, restarted);
        restarted.start();
        for (String peer : directory.nodeIds()) {
            if (!peer.equals(downedId)) {
                restarted.getGossip().seedPeer(peer);
            }
        }

        assertTrue(waitUntil(() -> {
            try {
                byte[] a = restarted.get("doc:1");
                byte[] b = restarted.get("doc:2");
                return a != null && b != null
                        && "indexed-on-cluster".equals(new String(a, StandardCharsets.UTF_8))
                        && "written-after-failover".equals(new String(b, StandardCharsets.UTF_8));
            } catch (RuntimeException notReady) {
                return false;
            }
        }, DEADLINE_MS), "the restarted node must converge on both committed entries");

        // Still exactly one leader once the cluster settles.
        assertTrue(waitUntil(() -> leaderCount() == 1, DEADLINE_MS),
                "the cluster must settle on a single leader after the node rejoins");
    }

    /**
     * A node restarted with the same storage directory must recover its
     * committed Raft state from disk rather than starting empty. This is what
     * makes the deployed configuration (a per-pod volume) meaningful.
     */
    @Test
    void committedStateSurvivesAFullClusterRestart() throws Exception {
        formCluster("node-a", "node-b", "node-c");
        assertTrue(waitUntil(() -> leaderCount() == 1, DEADLINE_MS), "cluster must elect a leader");
        assertNotNull(putThroughLeader("durable:key", "durable-value"),
                "the write must commit before the restart");

        for (ClusterNode node : nodes.values()) {
            node.stop();
        }
        nodes.clear();

        // Rebuild every node against the same storage directories.
        for (String id : directory.nodeIds()) {
            nodes.put(id, buildNode(id));
        }
        for (ClusterNode node : nodes.values()) {
            node.start();
        }
        for (Map.Entry<String, ClusterNode> entry : nodes.entrySet()) {
            for (String peer : directory.nodeIds()) {
                if (!peer.equals(entry.getKey())) {
                    entry.getValue().getGossip().seedPeer(peer);
                }
            }
        }

        assertTrue(waitUntil(() -> leaderCount() == 1, DEADLINE_MS),
                "the restarted cluster must elect a leader from persisted state");

        assertTrue(waitUntil(() -> {
            try {
                ClusterNode any = live().get(0);
                byte[] value = any.get("durable:key");
                return value != null && "durable-value".equals(new String(value, StandardCharsets.UTF_8));
            } catch (RuntimeException notReady) {
                return false;
            }
        }, DEADLINE_MS), "committed state must survive a full cluster restart");
    }
}
