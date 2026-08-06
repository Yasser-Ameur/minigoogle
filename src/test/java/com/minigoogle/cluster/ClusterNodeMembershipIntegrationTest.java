package com.minigoogle.cluster;

import com.minigoogle.cluster.state.ReplicatedKeyValueStore;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.storage.metadata.RaftAppliedStore;
import com.minigoogle.storage.metadata.RaftConfigurationStore;
import com.minigoogle.storage.metadata.RaftMetadataStore;
import com.minigoogle.storage.metadata.RaftSnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end Raft membership reconfiguration through {@link ClusterNode} over
 * real HTTP: with a committed configuration established, the quorum follows the
 * config and not gossip liveness (a dead member never shrinks it); addNode
 * brings a fourth server into every node's committed config and raises the
 * majority; removeNode shrinks it back and the removed server stops counting; a
 * leader removed by its own committed change steps down and the survivors
 * re-elect; a node restarted on its storage directory restores its config
 * before gossip converges; and a server added to a cluster whose log has been
 * compacted catches up via InstallSnapshot and adopts the config it joined.
 */
class ClusterNodeMembershipIntegrationTest {

    private static final String NODE_1 = "node-1";
    private static final String NODE_2 = "node-2";
    private static final String NODE_3 = "node-3";
    private static final String NODE_4 = "node-4";

    private static final int PORT_1 = 9201;
    private static final int PORT_2 = 9202;
    private static final int PORT_3 = 9203;
    private static final int PORT_4 = 9204;

    private static final long GOSSIP_INTERVAL = 100;
    private static final long GOSSIP_TIMEOUT = 500;
    private static final long RAFT_ELECTION = 600;
    private static final long RAFT_HEARTBEAT = 150;

    private static final long CONVERGENCE_DEADLINE_MS = 10_000;
    private static final long CATCH_UP_DEADLINE_MS = 20_000;

    private final Map<String, Integer> portMap = new ConcurrentHashMap<>();
    private final List<ClusterNode> started = new ArrayList<>();
    private ClusterSecurity security;
    private NodeDirectory directory;
    private ClusterNode node1;
    private ClusterNode node2;
    private ClusterNode node3;
    private ClusterNode node4;
    private ReplicatedKeyValueStore store1;
    private ReplicatedKeyValueStore store2;
    private ReplicatedKeyValueStore store3;

    @AfterEach
    void tearDown() {
        for (ClusterNode node : started) {
            node.stop();
        }
        started.clear();
    }

    @Test
    void testEstablishedQuorumDoesNotShrinkWhenFollowersDie() throws Exception {
        bootstrap3();
        convergeAndElect(List.of(node1, node2, node3));

        ClusterNode leader = currentLeader(List.of(node1, node2, node3));
        leader.put("k1", value(1));
        assertTrue(waitUntil(() -> appliedOnAll(List.of(node1, node2, node3), 1), CONVERGENCE_DEADLINE_MS),
                "The committed write must reach every member");

        // Stop both followers: gossip will mark them dead, but the committed
        // config {1,2,3} still demands a majority of 2. The leader alone must
        // not be able to establish a read barrier.
        List<ClusterNode> others = List.of(node1, node2, node3).stream()
                .filter(n -> n != leader)
                .toList();
        others.get(0).stop();
        others.get(1).stop();
        Thread.sleep(600); // let gossip reflect the deaths

        NotLeaderException ex = assertThrows(NotLeaderException.class, () -> leader.get("k1"),
                "A leader with the 2/3 quorum unreachable must not serve a linearizable read");
        assertTrue(ex.getMessage().contains("read barrier"),
                "The read must fail because the quorum cannot be established: " + ex.getMessage());
    }

    @Test
    void testAddNodeBringsFourthServerIntoEveryConfigAndRaisesQuorum() throws Exception {
        bootstrap3();
        convergeAndElect(List.of(node1, node2, node3));

        ClusterNode leader = currentLeader(List.of(node1, node2, node3));
        leader.put("k1", value(1));
        assertTrue(waitUntil(() -> appliedOnAll(List.of(node1, node2, node3), 1), CONVERGENCE_DEADLINE_MS),
                "The committed write must reach every member");

        ReplicatedKeyValueStore store4 = new ReplicatedKeyValueStore();
        node4 = newNode4(store4);
        node4.getGossip().seedPeer(NODE_2);
        start(node4);

        applyMembership(List.of(node1, node2, node3), NODE_4, true);

        assertTrue(waitUntil(() -> committedOnAll(List.of(node1, node2, node3, node4),
                Set.of(NODE_1, NODE_2, NODE_3, NODE_4)), CATCH_UP_DEADLINE_MS),
                () -> "The add must reach every committed config:\n" + dump(List.of(node1, node2, node3, node4)));
        assertTrue(waitUntil(() -> hasApplied(store4, 1), CATCH_UP_DEADLINE_MS),
                "The fourth server must catch up on the committed state");
        assertEquals(3, node4.getCommittedConfig().majority(),
                "The four-member config must demand a majority of 3");

        leader = currentLeader(List.of(node1, node2, node3, node4));
        leader.put("k2", value(2));
        assertTrue(waitUntil(() -> hasApplied(store4, 2), CATCH_UP_DEADLINE_MS),
                "The fourth server must receive replication after joining");

        // Two of four members down: leader + one survivor = 2 < 3, so the
        // leader must not serve a read. Had the config NOT grown, 2 would still
        // have been a majority of the old three-member cluster.
        final ClusterNode leaderOfFour = leader;
        List<ClusterNode> nonLeaders = List.of(node1, node2, node3, node4).stream()
                .filter(n -> n != leaderOfFour)
                .toList();
        assertTrue(nonLeaders.size() == 3);
        nonLeaders.get(0).stop();
        nonLeaders.get(1).stop();

        NotLeaderException ex = assertThrows(NotLeaderException.class, () -> leaderOfFour.get("k2"),
                "2 of 4 must not satisfy a 3-of-4 quorum");
        assertTrue(ex.getMessage().contains("read barrier"), ex.getMessage());
    }

    @Test
    void testRemoveNodeShrinksQuorumAndRemovedServerStopsCounting() throws Exception {
        bootstrap3();
        convergeAndElect(List.of(node1, node2, node3));

        ClusterNode leader = currentLeader(List.of(node1, node2, node3));
        leader.put("k1", value(1));

        ReplicatedKeyValueStore store4 = new ReplicatedKeyValueStore();
        node4 = newNode4(store4);
        node4.getGossip().seedPeer(NODE_2);
        start(node4);

        applyMembership(List.of(node1, node2, node3), NODE_4, true);
        assertTrue(waitUntil(() -> committedOnAll(List.of(node1, node2, node3, node4),
                Set.of(NODE_1, NODE_2, NODE_3, NODE_4)), CATCH_UP_DEADLINE_MS),
                () -> "The add must reach every committed config:\n" + dump(List.of(node1, node2, node3, node4)));

        applyMembership(List.of(node1, node2, node3), NODE_4, false);
        assertTrue(waitUntil(() -> committedOnAll(List.of(node1, node2, node3, node4),
                Set.of(NODE_1, NODE_2, NODE_3)), CATCH_UP_DEADLINE_MS),
                () -> "The remove must reach every committed config:\n" + dump(List.of(node1, node2, node3, node4)));
        assertEquals(2, node4.getCommittedConfig().majority(),
                "The three-member config must demand a majority of 2");
        assertFalse(node4.getCommittedConfig().contains(NODE_4),
                "The removed server must no longer be a member");

        // Stop a third member: leader + one survivor = 2 is exactly the
        // majority of {1,2,3}. If node-4 still counted (four-member, majority
        // 3), the same read would fail -- so success proves it stopped counting.
        node3.stop();
        assertTrue(waitUntil(() -> leaderAmong(List.of(node1, node2)) != null, CONVERGENCE_DEADLINE_MS),
                "The survivors must elect a leader after the third member is stopped");
        ClusterNode survivorLeader = leaderAmong(List.of(node1, node2));
        assertArrayEquals(value(1), survivorLeader.get("k1"),
                "The 2-of-3 quorum must be enough after the removal");
    }

    @Test
    void testLeaderRemovedByCommittedChangeStepsDownAndSurvivorsReelect() throws Exception {
        bootstrap3();
        convergeAndElect(List.of(node1, node2, node3));

        ClusterNode leader = currentLeader(List.of(node1, node2, node3));
        String leaderId = leader.getRaft().getNodeId();
        leader.put("k1", value(1));

        applyMembership(List.of(node1, node2, node3), leaderId, false);

        assertEquals(RaftConsensus.RaftState.FOLLOWER, leader.getRaft().getState(),
                "A leader removed by its own committed change must step down");
        assertFalse(leader.getCommittedConfig().contains(leaderId),
                "The removed leader must no longer be a member");

        List<ClusterNode> survivors = List.of(node1, node2, node3).stream()
                .filter(n -> n != leader)
                .toList();
        Set<String> expectedSurvivors = Set.copyOf(survivors.stream().map(n -> n.getRaft().getNodeId()).toList());
        assertTrue(waitUntil(() -> leaderAmong(survivors) != null, CONVERGENCE_DEADLINE_MS),
                "The survivors must re-elect a leader without a restart");
        ClusterNode newLeader = leaderAmong(survivors);
        assertTrue(waitUntil(() -> newLeader.getCommittedConfig().members().equals(expectedSurvivors),
                        CONVERGENCE_DEADLINE_MS),
                () -> "The survivors must form a two-member configuration:\n" + dump(survivors));
        newLeader.put("k2", value(2));
        assertArrayEquals(value(2), newLeader.get("k2"),
                "The new leader must serve reads and writes");
    }

    @Test
    void testRestartOnStorageDirectoryRestoresConfigBeforeGossipConverges(@TempDir Path dir) throws Exception {
        ClusterSecurity sec = new ClusterSecurity("membership-restart-secret");
        // node-2 and node-3 are unreachable: resolving them to this node's own
        // port would let the lone node's campaign RPCs loop back and count as
        // self-votes, defeating the quorum check the test is making.
        NodeDirectory directory = nodeId -> NODE_1.equals(nodeId)
                ? URI.create("http://127.0.0.1:" + PORT_1)
                : null;

        ClusterNode node = new ClusterNode(NODE_1, PORT_1, directory, GOSSIP_INTERVAL, GOSSIP_TIMEOUT,
                RAFT_ELECTION, RAFT_HEARTBEAT, null, sec, dir);
        try {
            // Start the server so stop() releases the port: the JDK HttpServer
            // does not unbind a listener that was stopped before ever being
            // started, which would make the rebind below fail on Windows.
            node.start();
            node.initializeConfig(List.of(NODE_1, NODE_2, NODE_3));
            assertTrue(node.getRaft().getConfigEstablished());
        } finally {
            node.stop();
        }

        // A fresh process on the same directory restores the committed config
        // from raft-config.bin before gossip has converged at all.
        ClusterNode restarted = new ClusterNode(NODE_1, PORT_1, directory, GOSSIP_INTERVAL, GOSSIP_TIMEOUT,
                RAFT_ELECTION, RAFT_HEARTBEAT, null, sec, dir);
        try {
            assertTrue(restarted.getRaft().getConfigEstablished());
            assertEquals(Set.of(NODE_1, NODE_2, NODE_3), restarted.getCommittedConfig().members(),
                    "The committed config must be restored before gossip converges");
            assertEquals(2, restarted.getCommittedConfig().majority(),
                    "The restored config must demand a quorum of 2");

            // Alone, the node can never gather the restored quorum: it keeps
            // campaigning and never wins, so it never self-elects as if it were
            // a one-node cluster.
            restarted.start();
            int t0 = restarted.getRaft().getCurrentTerm();
            assertTrue(waitUntil(() -> restarted.getRaft().getCurrentTerm() >= t0 + 2, 5000),
                    "The restarted node must keep campaigning without ever winning");
            assertNotEquals(RaftConsensus.RaftState.LEADER, restarted.getRaft().getState(),
                    "One live node cannot satisfy the restored 2-of-3 quorum");
        } finally {
            restarted.stop();
        }
    }

    @Test
    void testNodeAddedToCompactedClusterCatchesUpViaInstallSnapshotAndAdoptsConfig(@TempDir Path dir) throws Exception {
        Path dir1 = Files.createDirectories(dir.resolve("n1"));
        Path dir2 = Files.createDirectories(dir.resolve("n2"));
        Path dir3 = Files.createDirectories(dir.resolve("n3"));
        Path dir4 = Files.createDirectories(dir.resolve("n4"));

        portMap.put(NODE_1, PORT_1);
        portMap.put(NODE_2, PORT_2);
        portMap.put(NODE_3, PORT_3);
        portMap.put(NODE_4, PORT_4);
        security = new ClusterSecurity("membership-snapshot-secret");
        directory = nodeId -> {
            Integer port = portMap.get(nodeId);
            return port != null ? URI.create("http://127.0.0.1:" + port) : null;
        };

        Map<String, RaftSnapshotStore> snapStores = new ConcurrentHashMap<>();
        snapStores.put(NODE_1, new RaftSnapshotStore(dir1.resolve("raft-snapshot.bin")));
        snapStores.put(NODE_2, new RaftSnapshotStore(dir2.resolve("raft-snapshot.bin")));
        snapStores.put(NODE_3, new RaftSnapshotStore(dir3.resolve("raft-snapshot.bin")));
        snapStores.put(NODE_4, new RaftSnapshotStore(dir4.resolve("raft-snapshot.bin")));

        store1 = new ReplicatedKeyValueStore();
        store2 = new ReplicatedKeyValueStore();
        store3 = new ReplicatedKeyValueStore();
        ReplicatedKeyValueStore store4 = new ReplicatedKeyValueStore();

        node1 = newClusterNode(NODE_1, PORT_1, directory, store1, snapStores.get(NODE_1), 5, RaftConfigurationStore.inMemory());
        node2 = newClusterNode(NODE_2, PORT_2, directory, store2, snapStores.get(NODE_2), 5, RaftConfigurationStore.inMemory());
        node3 = newClusterNode(NODE_3, PORT_3, directory, store3, snapStores.get(NODE_3), 5, RaftConfigurationStore.inMemory());

        node1.initializeConfig(List.of(NODE_1, NODE_2, NODE_3));
        node2.initializeConfig(List.of(NODE_1, NODE_2, NODE_3));
        node3.initializeConfig(List.of(NODE_1, NODE_2, NODE_3));
        node1.getGossip().seedPeer(NODE_2);
        node2.getGossip().seedPeer(NODE_3);
        start(node1, node2, node3);

        try {
            convergeAndElect(List.of(node1, node2, node3));
            ClusterNode leader = currentLeader(List.of(node1, node2, node3));
            for (int i = 1; i <= 12; i++) {
                leader.put("k" + i, value(i));
            }
            assertTrue(waitUntil(() -> appliedOnAll(List.of(node1, node2, node3), 12), CONVERGENCE_DEADLINE_MS),
                    "Every entry must apply on every member");
            assertTrue(leader.getRaft().getLogFirstIndex() > 1,
                    "The log must be compacted past the snapshot base");
            RaftSnapshot leaderSnapshot = snapStores.get(leader.getRaft().getNodeId()).load();
            assertNotNull(leaderSnapshot, "The leader must have snapshotted past the small interval");
            assertEquals(Set.of(NODE_1, NODE_2, NODE_3), leaderSnapshot.config().members(),
                    "The leader's snapshot must carry the committed configuration");

            // A fresh, empty node joins the compacted cluster in bootstrap mode.
            node4 = newClusterNode(NODE_4, PORT_4, directory, store4, snapStores.get(NODE_4), 5,
                    RaftConfigurationStore.inMemory());
            node4.getGossip().seedPeer(NODE_2);
            start(node4);

            applyMembership(List.of(node1, node2, node3), NODE_4, true);

            assertTrue(waitUntil(() -> node4.getCommittedConfig().members()
                    .equals(Set.of(NODE_1, NODE_2, NODE_3, NODE_4)), CATCH_UP_DEADLINE_MS),
                    () -> "The joining node must adopt the config it joined:\n" + dump(List.of(node1, node2, node3, node4)));
            assertTrue(waitUntil(() -> hasApplied(store4, 12), CATCH_UP_DEADLINE_MS),
                    "The joining node must converge on the full committed KV");
            assertTrue(node4.getRaft().getLogFirstIndex() > 1,
                    "The empty node must catch up via InstallSnapshot, not a full log replay");
            RaftSnapshot installed = snapStores.get(NODE_4).load();
            assertNotNull(installed, "The joining node must install the leader's snapshot");
            assertEquals(Set.of(NODE_1, NODE_2, NODE_3), installed.config().members(),
                    "The installed snapshot must carry the committed config at its capture index");
        } finally {
            for (ClusterNode node : List.of(node1, node2, node3, node4)) {
                node.stop();
            }
        }
    }

    /**
     * Builds the standard three-node in-memory cluster with the committed
     * configuration {@code {node-1, node-2, node-3}} established up front, so
     * every quorum decision is config-driven from the first election.
     */
    private void bootstrap3() throws IOException {
        portMap.put(NODE_1, PORT_1);
        portMap.put(NODE_2, PORT_2);
        portMap.put(NODE_3, PORT_3);
        portMap.put(NODE_4, PORT_4);
        security = new ClusterSecurity("membership-integration-secret");
        directory = nodeId -> {
            Integer port = portMap.get(nodeId);
            return port != null ? URI.create("http://127.0.0.1:" + port) : null;
        };

        store1 = new ReplicatedKeyValueStore();
        store2 = new ReplicatedKeyValueStore();
        store3 = new ReplicatedKeyValueStore();

        node1 = newClusterNode(NODE_1, PORT_1, directory, store1, null, 0, null);
        node2 = newClusterNode(NODE_2, PORT_2, directory, store2, null, 0, null);
        node3 = newClusterNode(NODE_3, PORT_3, directory, store3, null, 0, null);

        node1.initializeConfig(List.of(NODE_1, NODE_2, NODE_3));
        node2.initializeConfig(List.of(NODE_1, NODE_2, NODE_3));
        node3.initializeConfig(List.of(NODE_1, NODE_2, NODE_3));

        node1.getGossip().seedPeer(NODE_2);
        node2.getGossip().seedPeer(NODE_3);

        start(node1, node2, node3);
    }

    private ClusterNode newNode4(ReplicatedKeyValueStore store) throws IOException {
        return newClusterNode(NODE_4, PORT_4, directory, store, null, 0, null);
    }

    private ClusterNode newClusterNode(String nodeId, int port, NodeDirectory directory,
                                       ReplicatedKeyValueStore store, RaftSnapshotStore snapshotStore,
                                       int snapshotInterval, RaftConfigurationStore configStore) throws IOException {
        return new ClusterNode(nodeId, port, directory, GOSSIP_INTERVAL, GOSSIP_TIMEOUT, RAFT_ELECTION,
                RAFT_HEARTBEAT, null, security, RaftMetadataStore.inMemory(), RaftLog.inMemory(), store,
                RaftAppliedStore.inMemory(), snapshotStore, snapshotInterval, configStore);
    }

    private void start(ClusterNode... nodes) {
        for (ClusterNode node : nodes) {
            started.add(node);
            node.start();
        }
    }

    /**
     * Issues a membership change against the current leader until it commits,
     * retrying across leadership changes. The operation itself blocks until the
     * entry is committed and applied on the leader, so a successful return
     * means the change is durable.
     */
    private void applyMembership(List<ClusterNode> members, String nodeId, boolean present) throws InterruptedException {
        long deadline = System.currentTimeMillis() + CATCH_UP_DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            ClusterNode leader = leaderAmong(members);
            if (leader != null) {
                try {
                    if (present) {
                        leader.addNode(nodeId);
                    } else {
                        leader.removeNode(nodeId);
                    }
                    return;
                } catch (NotLeaderException e) {
                    // Leadership moved mid-change or a change is still pending;
                    // re-issue on the current leader.
                }
            }
            Thread.sleep(100);
        }
        fail("Could not " + (present ? "add" : "remove") + " " + nodeId + " before the deadline");
    }

    private void convergeAndElect(List<ClusterNode> nodes) throws InterruptedException {
        assertTrue(waitUntil(() -> allLiveSetsConverged(nodes, Set.of(NODE_1, NODE_2, NODE_3)),
                CONVERGENCE_DEADLINE_MS), "Gossip did not converge:\n" + dump(nodes));
        stableLeaderAmong(nodes, 800);
        assertEquals(1, leadersIn(nodes), "Leadership must be stable before any write");
    }

    private ClusterNode currentLeader(List<ClusterNode> nodes) {
        ClusterNode leader = leaderAmong(nodes);
        if (leader == null) {
            throw new AssertionError("No leader found:\n" + dump(nodes));
        }
        return leader;
    }

    private ClusterNode leaderAmong(List<ClusterNode> nodes) {
        for (ClusterNode node : nodes) {
            if (node.getRaft().getState() == RaftConsensus.RaftState.LEADER) {
                return node;
            }
        }
        return null;
    }

    private long leadersIn(List<ClusterNode> nodes) {
        return nodes.stream()
                .filter(n -> n.getRaft().getState() == RaftConsensus.RaftState.LEADER)
                .count();
    }

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

    private boolean allLiveSetsConverged(List<ClusterNode> nodes, Set<String> expected) {
        for (ClusterNode node : nodes) {
            if (!Set.copyOf(node.getGossip().getLiveNodes()).equals(expected)) {
                return false;
            }
        }
        return true;
    }

    private boolean committedOnAll(List<ClusterNode> nodes, Set<String> expected) {
        for (ClusterNode node : nodes) {
            if (!node.getCommittedConfig().members().equals(expected)) {
                return false;
            }
        }
        return true;
    }

    private boolean appliedOnAll(List<ClusterNode> nodes, int index) {
        for (ClusterNode node : nodes) {
            if (node.getRaft().getLastApplied() < index) {
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

    private static String dump(List<ClusterNode> nodes) {
        StringBuilder sb = new StringBuilder();
        for (ClusterNode node : nodes) {
            sb.append(node.getRaft().getNodeId())
                    .append(" state=").append(node.getRaft().getState())
                    .append(" term=").append(node.getRaft().getCurrentTerm())
                    .append(" config=").append(node.getCommittedConfig().members())
                    .append(" applied=").append(node.getRaft().getLastApplied())
                    .append(" first=").append(node.getRaft().getLogFirstIndex())
                    .append(" live=").append(node.getGossip().getLiveNodes())
                    .append('\n');
        }
        return sb.toString();
    }

    private static byte[] value(int i) {
        return ("value-" + i).getBytes(StandardCharsets.UTF_8);
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
