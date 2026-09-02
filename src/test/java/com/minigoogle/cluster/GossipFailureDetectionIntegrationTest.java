package com.minigoogle.cluster;

import com.minigoogle.cluster.transport.NodeDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end failure detection through three real {@link ClusterNode}s over
 * HTTP: a stopped node is marked SUSPECT and then DEAD within a bounded time,
 * the ring drops it, and a fresh process on the same id and port rejoins and
 * restores the ring.
 */
class GossipFailureDetectionIntegrationTest {

    private static final String NODE_1 = "node-1";
    private static final String NODE_2 = "node-2";
    private static final String NODE_3 = "node-3";

    private static final int PORT_1 = 9301;
    private static final int PORT_2 = 9302;
    private static final int PORT_3 = 9303;

    private static final long GOSSIP_INTERVAL = 50;
    private static final long GOSSIP_TIMEOUT = 200;
    // The default constructor computes deadTimeoutMs = 3 * failureTimeoutMs
    // (600ms here) inside GossipProtocol; ClusterNode has no direct knob for it.
    private static final long RAFT_ELECTION = 5000;
    private static final long RAFT_HEARTBEAT = 1000;

    private static final long CONVERGENCE_DEADLINE_MS = 5_000;
    private static final long DEATH_DEADLINE_MS = 5_000;
    private static final long REJOIN_DEADLINE_MS = 8_000;

    private final Map<String, Integer> portMap = new ConcurrentHashMap<>();
    private final List<ClusterNode> started = new ArrayList<>();
    private ClusterSecurity security;
    private NodeDirectory directory;

    @AfterEach
    void tearDown() {
        for (ClusterNode node : started) {
            node.stop();
        }
        started.clear();
    }

    @Test
    void testStoppedNodeGoesSuspectThenDeadAndRejoinsOnFreshProcess() throws Exception {
        portMap.put(NODE_1, PORT_1);
        portMap.put(NODE_2, PORT_2);
        portMap.put(NODE_3, PORT_3);
        security = new ClusterSecurity("gossip-failure-detection-secret");
        directory = nodeId -> {
            Integer port = portMap.get(nodeId);
            return port != null ? URI.create("http://127.0.0.1:" + port) : null;
        };

        ClusterNode node1 = newNode(NODE_1, PORT_1);
        ClusterNode node2 = newNode(NODE_2, PORT_2);
        ClusterNode node3 = newNode(NODE_3, PORT_3);

        node1.getGossip().seedPeer(NODE_2);
        node2.getGossip().seedPeer(NODE_3);

        start(node1, node2, node3);

        assertTrue(waitUntil(() -> node1.getRing().nodeCount() == 3 && node2.getRing().nodeCount() == 3,
                        CONVERGENCE_DEADLINE_MS),
                "The three nodes must converge to a full ring before the failure is injected");

        node3.stop();
        started.remove(node3);

        assertTrue(waitUntil(() ->
                        node1.getGossip().getSuspectNodes().contains(NODE_3)
                                || node1.getGossip().getMembershipTable().get(NODE_3).status()
                                        == GossipProtocol.NodeStatus.DEAD,
                DEATH_DEADLINE_MS), "node-1 must mark the stopped node-3 SUSPECT within the failure timeout");

        assertTrue(waitUntil(() ->
                        node1.getGossip().getMembershipTable().get(NODE_3).status() == GossipProtocol.NodeStatus.DEAD
                                && node2.getGossip().getMembershipTable().get(NODE_3).status() == GossipProtocol.NodeStatus.DEAD,
                DEATH_DEADLINE_MS), "Both survivors must confirm node-3 DEAD within the dead timeout");

        assertTrue(waitUntil(() -> node1.getRing().nodeCount() == 2 && node2.getRing().nodeCount() == 2,
                        DEATH_DEADLINE_MS),
                "The ring must drop the confirmed-dead node on both survivors");

        // A fresh process on the same id and port rejoins the cluster.
        ClusterNode node3b = newNode(NODE_3, PORT_3);
        node3b.getGossip().seedPeer(NODE_2);
        start(node3b);

        assertTrue(waitUntil(() -> node1.getRing().nodeCount() == 3 && node2.getRing().nodeCount() == 3
                        && node3b.getRing().nodeCount() == 3,
                REJOIN_DEADLINE_MS),
                "The rings must return to three members once node-3 rejoins");
    }

    private ClusterNode newNode(String nodeId, int port) throws IOException {
        return new ClusterNode(nodeId, port, directory, GOSSIP_INTERVAL, GOSSIP_TIMEOUT,
                RAFT_ELECTION, RAFT_HEARTBEAT, null, security);
    }

    private void start(ClusterNode... nodes) {
        for (ClusterNode node : nodes) {
            started.add(node);
            node.start();
        }
    }

    private boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }
}
