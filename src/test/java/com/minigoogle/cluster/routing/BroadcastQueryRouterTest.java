package com.minigoogle.cluster.routing;

import com.minigoogle.cluster.GossipProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BroadcastQueryRouterTest {

    @Test
    void testResolveTargetsReturnsLiveNodes() {
        // Create a gossip protocol for a hypothetical local node
        GossipProtocol gossip = new GossipProtocol("node-1");
        
        // Add a few nodes to simulate a cluster
        gossip.receiveGossip("node-2", Map.of(
            "node-2", new GossipProtocol.GossipNodeState("node-2", 1, GossipProtocol.NodeStatus.ALIVE, System.currentTimeMillis()),
            "node-3", new GossipProtocol.GossipNodeState("node-3", 1, GossipProtocol.NodeStatus.ALIVE, System.currentTimeMillis())
        ));
        
        BroadcastQueryRouter router = new BroadcastQueryRouter(gossip);
        
        List<String> targets = router.resolveTargets("test query");
        
        assertEquals(3, targets.size());
        assertTrue(targets.contains("node-1"));
        assertTrue(targets.contains("node-2"));
        assertTrue(targets.contains("node-3"));
    }
    
    @Test
    void testResolveTargetsExcludesDeadNodes() {
        GossipProtocol gossip = new GossipProtocol("node-1");
        
        gossip.receiveGossip("node-2", Map.of(
            "node-2", new GossipProtocol.GossipNodeState("node-2", 1, GossipProtocol.NodeStatus.DEAD, System.currentTimeMillis()),
            "node-3", new GossipProtocol.GossipNodeState("node-3", 1, GossipProtocol.NodeStatus.ALIVE, System.currentTimeMillis())
        ));
        
        BroadcastQueryRouter router = new BroadcastQueryRouter(gossip);
        
        List<String> targets = router.resolveTargets("test query");
        
        assertEquals(2, targets.size());
        assertTrue(targets.contains("node-1"));
        assertTrue(targets.contains("node-3"));
    }
}
