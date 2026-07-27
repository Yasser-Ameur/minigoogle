package com.minigoogle.distributed.registry;

import com.minigoogle.distributed.model.NodeInfo;
import com.minigoogle.distributed.model.NodeRole;
import com.minigoogle.distributed.model.NodeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for NodeRegistry functionality. */
class NodeRegistryTest {

    @Test
    void testRegistrationAndHealthCheck() throws InterruptedException {
        // Short timeout for testing
        NodeRegistry registry = new NodeRegistry(100);

        NodeInfo node = new NodeInfo("index-1", "localhost", 8080, NodeRole.INDEX, NodeStatus.ONLINE, 0);
        registry.register(node);

        List<NodeInfo> onlineNodes = registry.getNodes(NodeRole.INDEX, NodeStatus.ONLINE);
        assertEquals(1, onlineNodes.size());

        // Wait for timeout
        Thread.sleep(150);
        registry.checkHealth();

        // Node should now be offline
        List<NodeInfo> offlineNodes = registry.getNodes(NodeRole.INDEX, NodeStatus.OFFLINE);
        assertEquals(1, offlineNodes.size());
        assertEquals("index-1", offlineNodes.get(0).nodeId());

        onlineNodes = registry.getNodes(NodeRole.INDEX, NodeStatus.ONLINE);
        assertTrue(onlineNodes.isEmpty());
    }

    @Test
    void testHeartbeatKeepsNodeAlive() throws InterruptedException {
        NodeRegistry registry = new NodeRegistry(200);

        NodeInfo node = new NodeInfo("index-1", "localhost", 8080, NodeRole.INDEX, NodeStatus.ONLINE, 0);
        registry.register(node);

        // Sleep briefly, then heartbeat
        Thread.sleep(100);
        registry.heartbeat("index-1");

        // Wait a bit more, but total time since last heartbeat should be < 200
        Thread.sleep(50);
        registry.checkHealth();

        List<NodeInfo> onlineNodes = registry.getNodes(NodeRole.INDEX, NodeStatus.ONLINE);
        assertEquals(1, onlineNodes.size());
    }
}
