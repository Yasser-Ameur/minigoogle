package com.minigoogle.cluster;

import com.minigoogle.cluster.GossipProtocol.GossipNodeState;
import com.minigoogle.cluster.GossipProtocol.NodeStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rejoin behavior of {@link GossipProtocol}: a node that stops and restarts
 * (or a fresh process on the same id) restarts its heartbeat counter at zero,
 * below the counter survivors froze when it left. Without special handling it
 * could never be accepted back, because survivors only adopt strictly higher
 * counters. The fixes: a node that contacts us is alive right now (its own
 * entry always refreshes liveness), and an ALIVE claim revives a suspect/dead
 * entry — without ever regressing the highest-known counter or freshness.
 */
class GossipProtocolTest {

    @Test
    void testRejoiningNodeWithLowerCounterRevivesSuspectEntry() {
        GossipProtocol survivor = new GossipProtocol("survivor");
        List<String> joined = new ArrayList<>();
        survivor.addListener(new RecordingListener(joined, new ArrayList<>()));
        survivor.receiveGossip("rejoiner", Map.of(
                "rejoiner", state("rejoiner", 42, NodeStatus.ALIVE, 0)));

        // The rejoiner leaves; the survivor freezes its counter as SUSPECT.
        survivor.suspect("rejoiner");
        assertFalse(survivor.getLiveNodes().contains("rejoiner"));
        assertEquals(42, survivor.getMembershipTable().get("rejoiner").heartbeatCounter());

        // The rejoiner restarts its counter at zero and contacts the survivor.
        survivor.receiveGossip("rejoiner", Map.of(
                "rejoiner", state("rejoiner", 0, NodeStatus.ALIVE, System.currentTimeMillis())));

        GossipNodeState revived = survivor.getMembershipTable().get("rejoiner");
        assertEquals(NodeStatus.ALIVE, revived.status(), "A contacting node must be revived to ALIVE");
        assertEquals(42, revived.heartbeatCounter(), "The highest-known counter must never regress");
        assertTrue(revived.lastSeen() >= System.currentTimeMillis() - 50,
                "Liveness freshness must never regress");
        assertTrue(survivor.getLiveNodes().contains("rejoiner"));
        assertTrue(joined.contains("rejoiner"), "Revival must notify listeners");
    }

    @Test
    void testSenderSelfEntryRefreshesLivenessWithoutCounterAdvance() {
        GossipProtocol survivor = new GossipProtocol("survivor");
        long stale = System.currentTimeMillis() - 10_000;
        survivor.receiveGossip("peer", Map.of(
                "peer", state("peer", 50, NodeStatus.ALIVE, stale)));
        assertEquals(stale, survivor.getMembershipTable().get("peer").lastSeen());

        // The peer restarted: its counter dropped to 1 but it is contacting us.
        survivor.receiveGossip("peer", Map.of(
                "peer", state("peer", 1, NodeStatus.ALIVE, System.currentTimeMillis())));

        GossipNodeState refreshed = survivor.getMembershipTable().get("peer");
        assertEquals(NodeStatus.ALIVE, refreshed.status());
        assertEquals(50, refreshed.heartbeatCounter(), "The counter must keep the highest-known value");
        assertTrue(refreshed.lastSeen() > stale, "The sender's own liveness must be refreshed");
        assertTrue(survivor.getLiveNodes().contains("peer"));
    }

    @Test
    void testAliveClaimRevivesDeadEntry() {
        GossipProtocol survivor = new GossipProtocol("survivor");
        List<String> joined = new ArrayList<>();
        survivor.addListener(new RecordingListener(joined, new ArrayList<>()));
        survivor.receiveGossip("returner", Map.of(
                "returner", state("returner", 7, NodeStatus.ALIVE, 0)));
        survivor.confirmDead("returner");
        assertFalse(survivor.getLiveNodes().contains("returner"));

        survivor.receiveGossip("returner", Map.of(
                "returner", state("returner", 0, NodeStatus.ALIVE, System.currentTimeMillis())));

        assertEquals(NodeStatus.ALIVE, survivor.getMembershipTable().get("returner").status());
        assertEquals(7, survivor.getMembershipTable().get("returner").heartbeatCounter(),
                "The highest-known counter must survive a restart");
        assertTrue(survivor.getLiveNodes().contains("returner"));
        assertTrue(joined.contains("returner"));
    }

    @Test
    void testThirdPartyLowerCounterDoesNotRegressHealthyEntry() {
        GossipProtocol survivor = new GossipProtocol("survivor");
        survivor.receiveGossip("peer", Map.of(
                "peer", state("peer", 50, NodeStatus.ALIVE, 0)));
        survivor.receiveGossip("peer", Map.of(
                "peer", state("peer", 60, NodeStatus.ALIVE, 0)));

        // Another node's stale view carries a lower counter for the healthy peer.
        survivor.receiveGossip("other", Map.of(
                "peer", state("peer", 3, NodeStatus.ALIVE, 0)));

        assertEquals(60, survivor.getMembershipTable().get("peer").heartbeatCounter(),
                "A third party must not regress a healthy node's counter");
        assertEquals(NodeStatus.ALIVE, survivor.getMembershipTable().get("peer").status());
    }

    private static GossipNodeState state(String nodeId, long counter, NodeStatus status, long lastSeen) {
        return new GossipNodeState(nodeId, counter, status, lastSeen);
    }

    private record RecordingListener(List<String> joined, List<String> left) implements MembershipListener {
        @Override
        public void onNodeJoined(String nodeId) {
            joined.add(nodeId);
        }

        @Override
        public void onNodeLeft(String nodeId) {
            left.add(nodeId);
        }
    }
}
