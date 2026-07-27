package com.minigoogle.distributed.recovery;

import com.minigoogle.distributed.registry.NodeRegistry;
import com.minigoogle.distributed.replication.ReplicaManager;
import com.minigoogle.distributed.sharding.ShardManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for RecoveryManager functionality. */
class RecoveryManagerTest {

    private NodeRegistry registry;
    private ReplicaManager replicaManager;
    private ShardManager shardManager;
    private RecoveryManager recoveryManager;

    @BeforeEach
    void setUp() {
        registry = new NodeRegistry();
        replicaManager = new ReplicaManager();
        shardManager = new ShardManager();
        recoveryManager = new RecoveryManager(registry, replicaManager, shardManager);
    }

    @Test
    void testHandleNodeFailure() {
        shardManager.assignShard(1, "node-A");
        shardManager.assignShard(2, "node-A");
        shardManager.assignShard(1, "node-B");
        replicaManager.assignReplicas(1, "node-A", List.of("node-B", "node-C"));

        List<Integer> affected = recoveryManager.handleNodeFailure("node-A");
        assertNotNull(affected);
        // node-A should be removed from shard assignments
        assertFalse(shardManager.getNodesForShard(1).contains("node-A"));
    }

    @Test
    void testPromoteReplica() {
        replicaManager.assignReplicas(1, "node-A", List.of("node-B"));
        boolean promoted = recoveryManager.promoteReplica(1);
        assertTrue(promoted);
        assertEquals("node-B", replicaManager.getPrimary(1));
    }

    @Test
    void testListenerNotification() {
        List<String> notified = new java.util.ArrayList<>();
        recoveryManager.addListener(new RecoveryManager.RecoveryListener() {
            @Override
            public void onNodeFailed(String nodeId, List<Integer> affectedShards) {
                notified.add("failed:" + nodeId);
            }
            @Override
            public void onReplicaPromoted(int shardId, String newPrimary) {
                notified.add("promoted:" + shardId);
            }
        });

        replicaManager.assignReplicas(1, "node-A", List.of("node-B"));
        shardManager.assignShard(1, "node-A");
        recoveryManager.handleNodeFailure("node-A");
        assertTrue(notified.contains("failed:node-A"));
    }
}
