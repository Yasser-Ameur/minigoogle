package com.minigoogle.distributed.registry;

import com.minigoogle.distributed.model.NodeInfo;
import com.minigoogle.distributed.model.NodeRole;
import com.minigoogle.distributed.model.NodeStatus;
import com.minigoogle.distributed.model.ShardInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the active cluster state, including registered nodes, shards,
 * and heartbeats.
 */
public class NodeRegistry {

    private final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();
    private final List<ShardInfo> shards = new CopyOnWriteArrayList<>();
    private final long timeoutMillis;

    public NodeRegistry(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public NodeRegistry() {
        this(15000); // 15 seconds default timeout
    }

    /**
     * Registers a new node or updates an existing one to ONLINE.
     */
    public void register(NodeInfo node) {
        long now = System.currentTimeMillis();
        nodes.put(node.nodeId(), new NodeInfo(
                node.nodeId(),
                node.host(),
                node.port(),
                node.role(),
                NodeStatus.ONLINE,
                now
        ));
    }

    /**
     * Updates the last heartbeat timestamp for a node.
     */
    public void heartbeat(String nodeId) {
        nodes.computeIfPresent(nodeId, (id, info) -> info.withHeartbeat(System.currentTimeMillis()).withStatus(NodeStatus.ONLINE));
    }

    /**
     * Checks all nodes and marks those whose last heartbeat was too long ago as OFFLINE.
     */
    public void checkHealth() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, NodeInfo> entry : nodes.entrySet()) {
            NodeInfo info = entry.getValue();
            if (info.status() == NodeStatus.ONLINE && (now - info.lastHeartbeat() > timeoutMillis)) {
                nodes.put(info.nodeId(), info.withStatus(NodeStatus.OFFLINE));
            }
        }
    }

    /**
     * Registers a shard, replacing any existing shard with the same ID.
     */
    public void registerShard(ShardInfo shard) {
        shards.removeIf(s -> s.shardId() == shard.shardId());
        shards.add(shard);
    }

    /**
     * Registers multiple shards at once.
     */
    public void registerShards(List<ShardInfo> newShards) {
        for (ShardInfo shard : newShards) {
            registerShard(shard);
        }
    }

    /**
     * Returns a snapshot of the registered shards.
     */
    public List<ShardInfo> getShards() {
        return new ArrayList<>(shards);
    }

    /**
     * Returns a snapshot of the current cluster state.
     */
    public ClusterState getState() {
        return new ClusterState(new ArrayList<>(nodes.values()), getShards());
    }

    /**
     * Returns all nodes matching a specific role and status.
     */
    public List<NodeInfo> getNodes(NodeRole role, NodeStatus status) {
        List<NodeInfo> result = new ArrayList<>();
        for (NodeInfo info : nodes.values()) {
            if (info.role() == role && info.status() == status) {
                result.add(info);
            }
        }
        return result;
    }
}
