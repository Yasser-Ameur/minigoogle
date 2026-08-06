package com.minigoogle.cluster;

import com.minigoogle.cluster.state.ReplicatedKeyValueStore;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.storage.metadata.RaftAppliedStore;
import com.minigoogle.storage.metadata.RaftMetadataStore;
import com.minigoogle.storage.wal.WriteAheadLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end linearizable key-value operations through {@link ClusterNode}
 * over real HTTP: leader writes commit and are readable, follower operations
 * surface the leader id for redirection, and a node restarted on durable
 * stores rebuilds the state machine from its log + watermark.
 */
class ClusterNodeKvIntegrationTest {

    private static final long CONVERGENCE_DEADLINE_MS = 8000;
    private static final byte[] V = "value-1".getBytes(StandardCharsets.UTF_8);

    private ClusterNode node1;
    private ClusterNode node2;
    private ClusterNode node3;
    private ReplicatedKeyValueStore store1;
    private ReplicatedKeyValueStore store2;
    private ReplicatedKeyValueStore store3;

    @BeforeEach
    void setUp() throws IOException {
        long gossipInterval = 100;
        long timeout = 500;
        long raftElection = 400;
        long raftHeartbeat = 150;

        Map<String, Integer> portMap = new ConcurrentHashMap<>();
        portMap.put("node-1", 9101);
        portMap.put("node-2", 9102);
        portMap.put("node-3", 9103);

        NodeDirectory directory = nodeId -> {
            Integer port = portMap.get(nodeId);
            return port != null ? URI.create("http://127.0.0.1:" + port) : null;
        };

        ClusterSecurity security = new ClusterSecurity("kv-integration-secret");
        store1 = new ReplicatedKeyValueStore();
        store2 = new ReplicatedKeyValueStore();
        store3 = new ReplicatedKeyValueStore();

        node1 = new ClusterNode("node-1", 9101, directory, gossipInterval, timeout, raftElection, raftHeartbeat,
                null, security, RaftMetadataStore.inMemory(), RaftLog.inMemory(), store1, RaftAppliedStore.inMemory());
        node2 = new ClusterNode("node-2", 9102, directory, gossipInterval, timeout, raftElection, raftHeartbeat,
                null, security, RaftMetadataStore.inMemory(), RaftLog.inMemory(), store2, RaftAppliedStore.inMemory());
        node3 = new ClusterNode("node-3", 9103, directory, gossipInterval, timeout, raftElection, raftHeartbeat,
                null, security, RaftMetadataStore.inMemory(), RaftLog.inMemory(), store3, RaftAppliedStore.inMemory());

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

    @Test
    void testLeaderPutCommitsAndGetReturnsValue() throws InterruptedException {
        convergeAndElect();

        ClusterNode leader = currentLeader();
        leader.put("doc:1", V);
        assertArrayEquals(V, leader.get("doc:1"), "Leader must read its own committed write");

        // Every follower must have applied the committed entry.
        assertTrue(waitUntil(() -> appliedOnAllFollowers(1), CONVERGENCE_DEADLINE_MS),
                "Followers must apply the committed entry");
        assertArrayEquals(V, store1.get("doc:1"));
        assertArrayEquals(V, store2.get("doc:1"));
        assertArrayEquals(V, store3.get("doc:1"));
    }

    @Test
    void testFollowerOperationsRaiseNotLeaderException() throws InterruptedException {
        convergeAndElect();

        ClusterNode leader = currentLeader();
        // Commit a write first: it pins the leader's term and pushes AppendEntries
        // to every follower, so leadership is stable when the exception is thrown.
        leader.put("k", V);
        assertTrue(waitUntil(() -> appliedOnAllFollowers(1), CONVERGENCE_DEADLINE_MS),
                "The committed write must reach every follower");

        ClusterNode follower = allNodes().stream()
                .filter(n -> n != leader)
                .findFirst().orElseThrow();

        NotLeaderException putEx = assertThrows(NotLeaderException.class, () -> follower.put("k2", V));
        assertEquals(leader.getRaft().getNodeId(), putEx.getLeaderId(), "The exception must name the real leader");

        assertThrows(NotLeaderException.class, () -> follower.delete("k2"));

        // Redirecting to the leader succeeds.
        leader.put("k2", V);
        assertArrayEquals(V, leader.get("k2"));
    }

    @Test
    void testFollowerServesLinearizableRead() throws InterruptedException {
        convergeAndElect();

        ClusterNode leader = currentLeader();
        leader.put("k", V);
        assertTrue(waitUntil(() -> appliedOnAllFollowers(1), CONVERGENCE_DEADLINE_MS),
                "The committed write must reach every follower");

        ClusterNode follower = allNodes().stream()
                .filter(n -> n != leader)
                .findFirst().orElseThrow();
        assertNotEquals(RaftConsensus.RaftState.LEADER, follower.getRaft().getState(),
                "Precondition: the serving node must be a follower");

        assertArrayEquals(V, follower.get("k"),
                "A follower must serve a linearizable read via the leader's read index");
        assertNull(follower.get("missing"), "Absent keys must read null on a follower too");
    }

    @Test
    void testLeaderDeleteRemovesValue() throws InterruptedException {
        convergeAndElect();

        ClusterNode leader = currentLeader();
        leader.put("k", V);
        assertArrayEquals(V, leader.get("k"));

        leader.delete("k");
        assertNull(leader.get("k"));
    }

    @Test
    void testPutAllCommitsAtomicallyOnEveryNode() throws InterruptedException {
        convergeAndElect();

        ClusterNode leader = currentLeader();
        byte[] v1 = "v1".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "v2".getBytes(StandardCharsets.UTF_8);
        leader.put("pre", V);
        leader.putAll(Map.of("txn:a", v1, "txn:b", v2));

        assertArrayEquals(v1, leader.get("txn:a"), "Leader must read its own transaction writes");
        assertArrayEquals(v2, leader.get("txn:b"));
        assertArrayEquals(V, leader.get("pre"), "Non-transaction state must be unaffected");

        int applied = leader.getRaft().getLastApplied();
        assertTrue(waitUntil(() -> appliedOnAllFollowers(applied), CONVERGENCE_DEADLINE_MS),
                "The transaction must apply to every follower");
        assertArrayEquals(v1, store1.get("txn:a"));
        assertArrayEquals(v2, store1.get("txn:b"));
        assertArrayEquals(v1, store2.get("txn:a"));
        assertArrayEquals(v2, store2.get("txn:b"));
        assertArrayEquals(v1, store3.get("txn:a"));
        assertArrayEquals(v2, store3.get("txn:b"));
    }

    @Test
    void testDeleteAllCommitsAtomicallyOnEveryNode() throws InterruptedException {
        convergeAndElect();

        ClusterNode leader = currentLeader();
        byte[] v1 = "v1".getBytes(StandardCharsets.UTF_8);
        leader.putAll(Map.of("txn:a", v1, "txn:b", v1, "txn:c", v1));
        assertArrayEquals(v1, leader.get("txn:a"));

        leader.deleteAll(Set.of("txn:a", "txn:b"));
        assertNull(leader.get("txn:a"));
        assertNull(leader.get("txn:b"));
        assertArrayEquals(v1, leader.get("txn:c"), "Keys outside the transaction must survive");

        int applied = leader.getRaft().getLastApplied();
        assertTrue(waitUntil(() -> appliedOnAllFollowers(applied), CONVERGENCE_DEADLINE_MS),
                "The delete transaction must apply to every follower");
        assertNull(store1.get("txn:a"));
        assertNull(store2.get("txn:b"));
        assertNull(store3.get("txn:a"));
        assertArrayEquals(v1, store3.get("txn:c"));
    }

    @Test
    void testFollowerTransactionRaisesNotLeaderException() throws InterruptedException {
        convergeAndElect();

        ClusterNode leader = currentLeader();
        leader.put("k", V);
        assertTrue(waitUntil(() -> appliedOnAllFollowers(1), CONVERGENCE_DEADLINE_MS),
                "The committed write must reach every follower");

        ClusterNode follower = allNodes().stream()
                .filter(n -> n != leader)
                .findFirst().orElseThrow();

        assertThrows(NotLeaderException.class, () -> follower.putAll(Map.of("a", V, "b", V)));
        assertThrows(NotLeaderException.class, () -> follower.deleteAll(Set.of("a", "b")));
    }

    @Test
    void testWriteAndReadSurviveNodeRestart() throws IOException, InterruptedException {
        ClusterSecurity security = new ClusterSecurity("kv-restart-secret");
        Path dir = Files.createTempDirectory("cluster-kv-restart");
        dir.toFile().deleteOnExit();
        long electionTimeout = 60_000; // keep the node out of auto-election during the test
        NodeDirectory directory = nodeId -> URI.create("http://127.0.0.1:9104");

        RaftMetadataStore metadataStore = new RaftMetadataStore(dir.resolve("raft-metadata.bin"));
        RaftLog durableLog = new RaftLog(new WriteAheadLog(dir.resolve("raft-log.bin")));
        RaftAppliedStore appliedStore = new RaftAppliedStore(dir.resolve("raft-applied.bin"));
        ReplicatedKeyValueStore kv = new ReplicatedKeyValueStore();

        ClusterNode node = new ClusterNode("node-solo", 9104, directory, 100, 500, electionTimeout, 150,
                null, security, metadataStore, durableLog, kv, appliedStore);
        node.start();
        try {
            makeLeader(node);
            node.put("k", V);
            assertArrayEquals(V, node.get("k"));
        } finally {
            node.stop();
        }

        // A fresh process on the same stores rebuilds the state machine from the
        // log + watermark before it even starts replicating.
        ReplicatedKeyValueStore rebuilt = new ReplicatedKeyValueStore();
        ClusterNode restarted = new ClusterNode("node-solo", 9104, directory, 100, 500, electionTimeout, 150,
                null, security, metadataStore,
                new RaftLog(new WriteAheadLog(dir.resolve("raft-log.bin"))), rebuilt, appliedStore);
        assertEquals(1, restarted.getRaft().getLastApplied(), "Restart must rebuild the applied prefix");
        assertArrayEquals(V, rebuilt.get("k"), "The rebuilt state machine must hold the committed value");

        restarted.start();
        try {
            makeLeader(restarted);
            assertArrayEquals(V, restarted.get("k"), "The restarted leader must serve the surviving write");
            restarted.put("k2", V);
            assertArrayEquals(V, restarted.get("k2"));
        } finally {
            restarted.stop();
        }
    }

    private void convergeAndElect() throws InterruptedException {
        assertTrue(waitUntil(this::allLiveSetsConverged, CONVERGENCE_DEADLINE_MS),
                "Gossip did not converge");
        // The first election is often contested; require the same single leader
        // to persist well past an election timeout so its term is stable before
        // any write is issued (a write racing a leadership change can never commit).
        waitForStableLeader(800);
        assertTrue(exactlyOneLeader(), "Leadership must be stable after the wait");
    }

    /**
     * Polls until the same node has been the only leader for {@code stabilityMs}
     * without interruption, then returns it.
     */
    private ClusterNode waitForStableLeader(long stabilityMs) throws InterruptedException {
        ClusterNode stable = null;
        long stableSince = 0;
        long deadline = System.currentTimeMillis() + CONVERGENCE_DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            ClusterNode leader = currentLeaderOrNull();
            if (leader != null && leader == stable) {
                if (System.currentTimeMillis() - stableSince >= stabilityMs) {
                    return leader;
                }
            } else {
                stable = leader;
                stableSince = System.currentTimeMillis();
            }
            Thread.sleep(25);
        }
        throw new AssertionError("No single leader remained stable for " + stabilityMs + "ms");
    }

    private ClusterNode currentLeaderOrNull() {
        for (ClusterNode node : allNodes()) {
            if (node.getRaft().getState() == RaftConsensus.RaftState.LEADER) {
                return node;
            }
        }
        return null;
    }

    private boolean appliedOnAllFollowers(int index) {
        for (ClusterNode node : allNodes()) {
            if (node.getRaft().getState() != RaftConsensus.RaftState.LEADER
                    && node.getRaft().getLastApplied() < index) {
                return false;
            }
        }
        return true;
    }

    private ClusterNode currentLeader() {
        for (ClusterNode node : allNodes()) {
            if (node.getRaft().getState() == RaftConsensus.RaftState.LEADER) {
                return node;
            }
        }
        throw new AssertionError("No leader found");
    }

    private List<ClusterNode> allNodes() {
        return List.of(node1, node2, node3);
    }

    private boolean allLiveSetsConverged() {
        Set<String> expected = Set.of("node-1", "node-2", "node-3");
        return Set.copyOf(node1.getGossip().getLiveNodes()).equals(expected)
                && Set.copyOf(node2.getGossip().getLiveNodes()).equals(expected)
                && Set.copyOf(node3.getGossip().getLiveNodes()).equals(expected);
    }

    private boolean exactlyOneLeader() {
        long leaders = allNodes().stream()
                .filter(n -> n.getRaft().getState() == RaftConsensus.RaftState.LEADER)
                .count();
        return leaders == 1;
    }

    private void makeLeader(ClusterNode node) {
        node.getRaft().startElection();
        assertTrue(node.getRaft().receiveVote(), "A single-node cluster must win its own election");
        node.getRaft().becomeLeader();
        assertEquals(RaftConsensus.RaftState.LEADER, node.getRaft().getState());
    }

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
}
