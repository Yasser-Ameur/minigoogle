package com.minigoogle.cluster;

import com.minigoogle.cluster.state.ReplicatedKeyValueStore;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.storage.metadata.RaftAppliedStore;
import com.minigoogle.storage.metadata.RaftMetadataStore;
import com.minigoogle.storage.metadata.RaftSnapshotStore;
import com.minigoogle.storage.wal.WriteAheadLog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end snapshot-driven log compaction through {@link ClusterNode} over
 * real HTTP: a single node commits past a small interval on real storage and
 * restarts on its directory rebuilding the KV from snapshot + compacted tail,
 * and a re-joining follower whose next index falls below the leader's first
 * retained index is caught up via InstallSnapshot.
 */
class ClusterNodeSnapshotIntegrationTest {

    private static final long CONVERGENCE_DEADLINE_MS = 10_000;
    private static final long CATCH_UP_DEADLINE_MS = 20_000;

    private static final String NODE_1 = "node-1";
    private static final String NODE_2 = "node-2";
    private static final String NODE_3 = "node-3";

    @Test
    void testLeaderRestartOnStorageDirectoryRebuildsFromSnapshotAndTail() throws IOException, InterruptedException {
        long electionTimeout = 60_000; // keep the node out of auto-election during the test
        Path dir = Files.createTempDirectory("cluster-snapshot-restart");
        dir.toFile().deleteOnExit();
        NodeDirectory directory = nodeId -> URI.create("http://127.0.0.1:9131");
        ClusterSecurity security = new ClusterSecurity("snapshot-restart-secret");

        RaftMetadataStore metadataStore = new RaftMetadataStore(dir.resolve("raft-metadata.bin"));
        RaftLog durableLog = new RaftLog(new WriteAheadLog(dir.resolve("raft-log.bin")));
        RaftAppliedStore appliedStore = new RaftAppliedStore(dir.resolve("raft-applied.bin"));
        RaftSnapshotStore snapshotStore = new RaftSnapshotStore(dir.resolve("raft-snapshot.bin"));
        ReplicatedKeyValueStore kv = new ReplicatedKeyValueStore();

        ClusterNode node = new ClusterNode("node-solo", 9131, directory, 100, 500, electionTimeout, 150,
                null, security, metadataStore, durableLog, kv, appliedStore, snapshotStore, 3);
        node.start();
        try {
            makeLeader(node);
            for (int i = 1; i <= 10; i++) {
                node.put("k" + i, value(i));
            }

            RaftSnapshot snapshot = snapshotStore.load();
            assertNotNull(snapshot, "Committing past the interval must produce a durable snapshot");
            assertEquals(9, snapshot.lastIncludedIndex(), "The snapshot must sit at an interval boundary");
            assertTrue(node.getRaft().getLogFirstIndex() > 1,
                    "The log must be compacted once the prefix is captured");
            for (int i = 1; i <= 10; i++) {
                assertArrayEquals(value(i), node.get("k" + i));
            }
        } finally {
            node.stop();
        }

        // A fresh process on the same directory must reopen the compacted WAL
        // at the snapshot's base and rebuild the state machine before starting.
        RaftSnapshot restored = snapshotStore.load();
        assertNotNull(restored, "The snapshot must survive the restart");
        ReplicatedKeyValueStore rebuilt = new ReplicatedKeyValueStore();
        RaftLog replayed = new RaftLog(new WriteAheadLog(dir.resolve("raft-log.bin")),
                restored.lastIncludedIndex(), restored.lastIncludedTerm());
        ClusterNode restarted = new ClusterNode("node-solo", 9131, directory, 100, 500, electionTimeout, 150,
                null, security, metadataStore, replayed, rebuilt, appliedStore, snapshotStore, 3);
        assertEquals(10, restarted.getRaft().getLastApplied(), "Restart must rebuild the applied prefix");
        assertEquals(10, restarted.getRaft().getLogFirstIndex(), "The log base must come from the snapshot");
        for (int i = 1; i <= 10; i++) {
            assertArrayEquals(value(i), rebuilt.get("k" + i), "Rebuilt state must hold every committed key");
        }

        restarted.start();
        try {
            makeLeader(restarted);
            assertArrayEquals(value(10), restarted.get("k10"), "The restarted leader must serve committed state");
            restarted.put("k11", value(11));
            assertArrayEquals(value(11), restarted.get("k11"), "The restarted leader must accept new writes");
        } finally {
            restarted.stop();
        }
    }

    @Test
    void testRejoiningFollowerCatchesUpViaInstallSnapshot() throws Exception {
        long gossipInterval = 100;
        long gossipTimeout = 500;
        long raftElection = 400;
        long raftHeartbeat = 150;

        Map<String, Integer> portMap = new ConcurrentHashMap<>();
        portMap.put(NODE_1, 9132);
        portMap.put(NODE_2, 9133);
        portMap.put(NODE_3, 9134);
        NodeDirectory directory = nodeId -> {
            Integer port = portMap.get(nodeId);
            return port != null ? URI.create("http://127.0.0.1:" + port) : null;
        };
        ClusterSecurity security = new ClusterSecurity("snapshot-catchup-secret");
        Path dir = Files.createTempDirectory("cluster-snapshot-catchup");
        dir.toFile().deleteOnExit();

        ReplicatedKeyValueStore store1 = new ReplicatedKeyValueStore();
        ReplicatedKeyValueStore store2 = new ReplicatedKeyValueStore();
        ReplicatedKeyValueStore store3 = new ReplicatedKeyValueStore();

        ClusterNode node1 = newNode(NODE_1, 9132, directory, gossipInterval, gossipTimeout, raftElection,
                raftHeartbeat, security, store1, new RaftSnapshotStore(dir.resolve("node-1-raft-snapshot.bin")));
        ClusterNode node2 = newNode(NODE_2, 9133, directory, gossipInterval, gossipTimeout, raftElection,
                raftHeartbeat, security, store2, new RaftSnapshotStore(dir.resolve("node-2-raft-snapshot.bin")));
        ClusterNode node3 = newNode(NODE_3, 9134, directory, gossipInterval, gossipTimeout, raftElection,
                raftHeartbeat, security, store3, new RaftSnapshotStore(dir.resolve("node-3-raft-snapshot.bin")));

        node1.getGossip().seedPeer(NODE_2);
        node2.getGossip().seedPeer(NODE_3);

        node1.start();
        node2.start();
        node3.start();
        boolean node3Stopped = false;
        try {
            List<ClusterNode> cluster = List.of(node1, node2, node3);
            assertTrue(waitUntil(() -> allLiveSetsConverged(cluster), CONVERGENCE_DEADLINE_MS),
                    "Gossip did not converge");
            ClusterNode leader = stableLeaderAmong(cluster, 800);
            assertTrue(exactlyOneLeader(cluster), "Leadership must be stable before any write");

            for (int i = 1; i <= 6; i++) {
                leader.put("k" + i, value(i));
            }
            assertTrue(waitUntil(() -> appliedOn(cluster, 6), CONVERGENCE_DEADLINE_MS),
                    "All three nodes must apply the first six entries");

            // Lose node-3, then push the surviving majority well past its position.
            node3.stop();
            node3Stopped = true;
            assertTrue(waitUntil(() -> leaderAmong(List.of(node1, node2)) != null, CONVERGENCE_DEADLINE_MS),
                    "A surviving node must take over leadership");
            leader = stableLeaderAmong(List.of(node1, node2), 800);
            for (int i = 7; i <= 20; i++) {
                leader.put("k" + i, value(i));
            }
            assertEquals(20, leader.getRaft().getLastApplied(), "Every entry must commit on the leader");
            assertTrue(leader.getRaft().getLogFirstIndex() > 7,
                    "The leader must compact the log past node-3's position");
            assertTrue(waitUntil(() -> bothCompactedPast(List.of(node1, node2), 7), CONVERGENCE_DEADLINE_MS),
                    "Both surviving nodes must compact past node-3's position so neither can serve the tail via log replication");

            // A fresh node-3 process rejoins on the same port. Its next index
            // falls below the leader's first retained index, so it must be
            // caught up by InstallSnapshot and then serve the full KV.
            ReplicatedKeyValueStore store3b = new ReplicatedKeyValueStore();
            RaftSnapshotStore snapshotStore3b = new RaftSnapshotStore(dir.resolve("node-3-raft-snapshot.bin"));
            ClusterNode node3b = newNode(NODE_3, 9134, directory, gossipInterval, gossipTimeout, raftElection,
                    raftHeartbeat, security, store3b, snapshotStore3b);
            node3b.getGossip().seedPeer(NODE_2);
            node3b.start();
            try {
                String trace = awaitCatchUp(node1, node2, node3b, store3b);
                assertTrue(trace == null, "The rejoining follower must converge to the full committed KV:\n" + trace);
                for (int i = 1; i <= 20; i++) {
                    assertArrayEquals(value(i), store3b.get("k" + i), "key " + i);
                }
                RaftSnapshot installed = snapshotStore3b.load();
                assertNotNull(installed, "The rejoining follower must install the leader's snapshot");
                assertTrue(installed.lastIncludedIndex() >= 7,
                        "The installed snapshot must cover node-3's position");
                assertEquals(20, node3b.getRaft().getLastApplied());
            } finally {
                node3b.stop();
            }
        } finally {
            node1.stop();
            node2.stop();
            if (!node3Stopped) {
                node3.stop();
            }
        }
    }

    private static ClusterNode newNode(String nodeId, int port, NodeDirectory directory, long gossipInterval,
                                       long gossipTimeout, long raftElection, long raftHeartbeat,
                                       ClusterSecurity security, ReplicatedKeyValueStore store,
                                       RaftSnapshotStore snapshotStore) throws IOException {
        return new ClusterNode(nodeId, port, directory, gossipInterval, gossipTimeout, raftElection, raftHeartbeat,
                null, security, RaftMetadataStore.inMemory(), RaftLog.inMemory(), store,
                RaftAppliedStore.inMemory(), snapshotStore, 2);
    }

    private static byte[] value(int i) {
        return ("value-" + i).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Waits for the rejoining follower to hold all {@code count} keys, or
     * returns a sampled state trace for diagnosis when it never does.
     *
     * @return {@code null} when caught up, otherwise a diagnostic trace.
     */
    private static String awaitCatchUp(ClusterNode node1, ClusterNode node2, ClusterNode node3b,
                                       ReplicatedKeyValueStore store3b) throws InterruptedException {
        StringBuilder trace = new StringBuilder();
        long deadline = System.currentTimeMillis() + CATCH_UP_DEADLINE_MS;
        long lastSample = 0;
        while (System.currentTimeMillis() < deadline) {
            if (hasApplied(store3b, 20)) {
                return null;
            }
            long now = System.currentTimeMillis();
            if (now - lastSample >= 1000) {
                lastSample = now;
                int applied = 0;
                for (int i = 1; i <= 20; i++) {
                    if (Arrays.equals(value(i), store3b.get("k" + i))) applied++;
                }
                trace.append(String.format(
                        "n1: state=%s live=%s | n2: state=%s live=%s | n3b: state=%s term=%d applied=%d first=%d last=%d live=%s keys=%d%n",
                        node1.getRaft().getState(), node1.getGossip().getLiveNodes(),
                        node2.getRaft().getState(), node2.getGossip().getLiveNodes(),
                        node3b.getRaft().getState(), node3b.getRaft().getCurrentTerm(),
                        node3b.getRaft().getLastApplied(), node3b.getRaft().getLogFirstIndex(),
                        node3b.getRaft().getLastLogIndex(), node3b.getGossip().getLiveNodes(), applied));
            }
            Thread.sleep(50);
        }
        return trace.toString();
    }

    private static String dump(ClusterNode node1, ClusterNode node2, ClusterNode node3,
                               ReplicatedKeyValueStore store3, RaftSnapshotStore snapshotStore3) {
        StringBuilder sb = new StringBuilder();
        for (ClusterNode node : List.of(node1, node2, node3)) {
            sb.append('\n').append(node.getRaft().getNodeId())
                    .append(" state=").append(node.getRaft().getState())
                    .append(" term=").append(node.getRaft().getCurrentTerm())
                    .append(" lastApplied=").append(node.getRaft().getLastApplied())
                    .append(" lastIndex=").append(node.getRaft().getLastLogIndex())
                    .append(" firstIndex=").append(node.getRaft().getLogFirstIndex())
                    .append(" live=").append(node.getGossip().getLiveNodes());
        }
        try {
            sb.append("\nnode3 snapshot=").append(snapshotStore3.load() == null ? "null" : "present");
        } catch (IOException e) {
            sb.append("\nnode3 snapshot=load-failed");
        }
        int applied = 0;
        for (int i = 1; i <= 20; i++) {
            if (Arrays.equals(value(i), store3.get("k" + i))) applied++;
        }
        sb.append(" store3-applied-keys=").append(applied);
        return sb.toString();
    }

    private boolean allLiveSetsConverged(List<ClusterNode> nodes) {
        Set<String> expected = Set.of(NODE_1, NODE_2, NODE_3);
        for (ClusterNode node : nodes) {
            if (!Set.copyOf(node.getGossip().getLiveNodes()).equals(expected)) {
                return false;
            }
        }
        return true;
    }

    private boolean exactlyOneLeader(List<ClusterNode> nodes) {
        return nodes.stream()
                .filter(n -> n.getRaft().getState() == RaftConsensus.RaftState.LEADER)
                .count() == 1;
    }

    private ClusterNode leaderAmong(List<ClusterNode> nodes) {
        for (ClusterNode node : nodes) {
            if (node.getRaft().getState() == RaftConsensus.RaftState.LEADER) {
                return node;
            }
        }
        return null;
    }

    /**
     * Polls until the same node has been the only leader among {@code nodes}
     * for {@code stabilityMs} without interruption, then returns it.
     */
    private ClusterNode stableLeaderAmong(List<ClusterNode> nodes, long stabilityMs) throws InterruptedException {
        ClusterNode stable = null;
        long stableSince = 0;
        long deadline = System.currentTimeMillis() + CONVERGENCE_DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            ClusterNode leader = leaderAmong(nodes);
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

    private boolean appliedOn(List<ClusterNode> nodes, int index) {
        for (ClusterNode node : nodes) {
            if (node.getRaft().getLastApplied() < index) {
                return false;
            }
        }
        return true;
    }

    private boolean bothCompactedPast(List<ClusterNode> nodes, int index) {
        for (ClusterNode node : nodes) {
            if (node.getRaft().getLogFirstIndex() <= index) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasApplied(ReplicatedKeyValueStore store, int count) {
        for (int i = 1; i <= count; i++) {
            byte[] got = store.get("k" + i);
            if (got == null || !Arrays.equals(value(i), got)) {
                return false;
            }
        }
        return true;
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
