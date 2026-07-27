package com.minigoogle.distributed.recovery;

import com.minigoogle.distributed.registry.NodeRegistry;
import com.minigoogle.distributed.replication.ReplicaManager;
import com.minigoogle.distributed.sharding.ShardManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Handles node failure recovery.
 *
 * Recovery flow (per ARCHITECTURE.md section 17):
 * 1. Coordinator detects missing heartbeats via NodeRegistry.
 * 2. Affected shards are identified.
 * 3. A replica is promoted to primary for each affected shard.
 * 4. Queries continue to be served without downtime.
 * 5. When a replacement node joins, replicas are rebuilt.
 */
public class RecoveryManager {

    private final NodeRegistry nodeRegistry;
    private final ReplicaManager replicaManager;
    private final ShardManager shardManager;
    private final List<RecoveryListener> listeners = new ArrayList<>();

    public RecoveryManager(NodeRegistry nodeRegistry, ReplicaManager replicaManager,
                           ShardManager shardManager) {
        this.nodeRegistry = nodeRegistry;
        this.replicaManager = replicaManager;
        this.shardManager = shardManager;
    }

    /**
     * Handles a node failure. Promotes replicas for all shards
     * that the failed node was responsible for.
     *
     * @param nodeId The ID of the failed node.
     * @return The list of shard IDs that were recovered.
     */
    public List<Integer> handleNodeFailure(String nodeId) {
        // Step 1: Mark node as offline in the registry
        nodeRegistry.checkHealth();

        // Step 2: Find all shards that the failed node owned
        Set<Integer> affectedShards = shardManager.removeNode(nodeId);

        // Step 3: Remove the node from all replica assignments
        // This triggers replica promotion for any shard where the
        // failed node was the primary
        replicaManager.removeNode(nodeId);

        // Step 4: Notify listeners
        List<Integer> recovered = new ArrayList<>(affectedShards);
        for (RecoveryListener listener : listeners) {
            listener.onNodeFailed(nodeId, recovered);
        }

        return recovered;
    }

    /**
     * Manually promotes a replica for a specific shard.
     *
     * @return true if promotion succeeded, false if no replica available.
     */
    public boolean promoteReplica(int shardId) {
        boolean promoted = replicaManager.promoteReplica(shardId);
        if (promoted) {
            for (RecoveryListener listener : listeners) {
                listener.onReplicaPromoted(shardId, replicaManager.getPrimary(shardId));
            }
        }
        return promoted;
    }

    /**
     * Registers a listener for recovery events.
     */
    public void addListener(RecoveryListener listener) {
        listeners.add(listener);
    }

    /**
     * Callback interface for recovery events.
     */
    public interface RecoveryListener {
        void onNodeFailed(String nodeId, List<Integer> affectedShards);

        void onReplicaPromoted(int shardId, String newPrimary);
    }
}
