package com.minigoogle.cluster;

import com.minigoogle.cluster.GossipProtocol.GossipNodeState;
import com.minigoogle.cluster.GossipProtocol.NodeStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A peer that is talking to us is alive, whatever its own table claims. Without
 * this rule a stale or hostile self-report could demote a reachable peer in the
 * same exchange that just proved it reachable.
 */
class GossipSelfReportTest {

    @Test
    void aSenderCannotDemoteItself() {
        GossipProtocol local = new GossipProtocol("local");
        AtomicInteger left = new AtomicInteger();
        local.addListener(new MembershipListener() {
            @Override public void onNodeJoined(String nodeId) { }
            @Override public void onNodeLeft(String nodeId) { left.incrementAndGet(); }
        });
        long now = System.currentTimeMillis();
        local.receiveGossip("peer", Map.of("peer", new GossipNodeState("peer", 10, NodeStatus.ALIVE, now)));
        assertEquals(NodeStatus.ALIVE, local.getMembershipTable().get("peer").status());

        // The peer's own entry claims DEAD with a lower counter than we hold.
        local.receiveGossip("peer", Map.of("peer", new GossipNodeState("peer", 3, NodeStatus.DEAD, now - 1)));

        GossipNodeState seen = local.getMembershipTable().get("peer");
        assertEquals(NodeStatus.ALIVE, seen.status());
        assertEquals(10, seen.heartbeatCounter());
        assertEquals(0, left.get());
    }

    @Test
    void aThirdPartysStaleAliveCopyDoesNotReviveASuspect() {
        GossipProtocol local = new GossipProtocol("local", 100, 200, 600, null);
        long old = System.currentTimeMillis() - 10_000;
        local.receiveGossip("a", Map.of("c", new GossipNodeState("c", 10, NodeStatus.ALIVE, old)));
        local.suspect("c");
        assertEquals(NodeStatus.SUSPECT, local.getMembershipTable().get("c").status());

        // Survivor a still holds its frozen ALIVE copy of c with the same counter.
        local.receiveGossip("a", Map.of("c", new GossipNodeState("c", 10, NodeStatus.ALIVE, old)));
        assertEquals(NodeStatus.SUSPECT, local.getMembershipTable().get("c").status());
        assertEquals(old, local.getMembershipTable().get("c").lastSeen(), "suspicion must not refresh the contact clock");

        // A fresher counter means c really is alive somewhere.
        local.receiveGossip("a", Map.of("c", new GossipNodeState("c", 11, NodeStatus.ALIVE, System.currentTimeMillis())));
        assertEquals(NodeStatus.ALIVE, local.getMembershipTable().get("c").status());
    }

    @Test
    void aRestartedNodeOutranksItsOldCounter() throws InterruptedException {
        GossipProtocol first = new GossipProtocol("n");
        long before = first.heartbeat().heartbeatCounter();
        Thread.sleep(5);
        GossipProtocol restarted = new GossipProtocol("n");
        assertEquals(true, restarted.getMembershipTable().get("n").heartbeatCounter() >= before,
                "a fresh instance must not start below its previous life's counter");
    }

    @Test
    void aThirdPartyReportStillNeedsAFresherCounter() {
        GossipProtocol local = new GossipProtocol("local");
        long now = System.currentTimeMillis();
        local.receiveGossip("a", Map.of("b", new GossipNodeState("b", 10, NodeStatus.ALIVE, now)));
        local.receiveGossip("a", Map.of("b", new GossipNodeState("b", 3, NodeStatus.DEAD, now - 1)));
        assertEquals(NodeStatus.ALIVE, local.getMembershipTable().get("b").status());
    }
}
