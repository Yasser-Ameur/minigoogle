package com.minigoogle.distributed.query.coordinator;

import com.minigoogle.distributed.model.NodeInfo;
import com.minigoogle.distributed.model.NodeRole;
import com.minigoogle.distributed.model.NodeStatus;
import com.minigoogle.distributed.model.ShardInfo;
import com.minigoogle.distributed.registry.ClusterState;
import com.minigoogle.distributed.registry.NodeRegistry;

import java.util.*;

/**
 * Routes queries to the correct shard nodes based on the current cluster state.
 * Determines which index nodes are eligible to serve each shard.
 */
public class QueryDispatcher {

    private final NodeRegistry registry;

    public QueryDispatcher(NodeRegistry registry) {
        this.registry = registry;
    }

    /**
     * Returns a mapping of shardId → target node URL for all active shards.
     * Selects the primary if available, otherwise falls back to replicas.
     */
    public Map<Integer, String> resolveTargets() {
        ClusterState state = registry.getState();
        if (state == null || state.nodes().isEmpty() || state.shards().isEmpty()) {
            return Map.of();
        }

        // Build map of online index nodes
        Map<String, NodeInfo> onlineNodes = new HashMap<>();
        for (NodeInfo node : state.nodes()) {
            if (node.status() == NodeStatus.ONLINE && node.role() == NodeRole.INDEX) {
                onlineNodes.put(node.nodeId(), node);
            }
        }

        Map<Integer, String> targets = new HashMap<>();
        for (ShardInfo shard : state.shards()) {
            // Try primary first
            NodeInfo primary = onlineNodes.get(shard.primaryNodeId());
            if (primary != null) {
                targets.put(shard.shardId(), toUrl(primary));
                continue;
            }

            // Fallback to replicas
            if (shard.replicaNodeIds() != null) {
                for (String replicaId : shard.replicaNodeIds()) {
                    NodeInfo replica = onlineNodes.get(replicaId);
                    if (replica != null) {
                        targets.put(shard.shardId(), toUrl(replica));
                        break;
                    }
                }
            }
        }

        return targets;
    }

    private String toUrl(NodeInfo node) {
        return "http://" + node.host() + ":" + node.port();
    }
}
