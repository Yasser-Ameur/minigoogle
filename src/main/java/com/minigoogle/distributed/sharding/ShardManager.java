package com.minigoogle.distributed.sharding;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Cluster-level shard assignment tracker.
 *
 * Unlike {@code storage.shard.ShardManager} which manages local shards
 * on a single node, this class maintains the global view of which nodes
 * own which shards across the entire cluster.
 *
 * Used by the coordinator to route queries and plan rebalancing.
 */
public class ShardManager {

    private final Map<Integer, Set<String>> shardToNodes = new HashMap<>();
    private final Map<String, Set<Integer>> nodeToShards = new HashMap<>();

    /**
     * Assigns a shard to a node. The node will be responsible for
     * indexing and serving queries for that shard.
     */
    public void assignShard(int shardId, String nodeId) {
        shardToNodes.computeIfAbsent(shardId, k -> new HashSet<>()).add(nodeId);
        nodeToShards.computeIfAbsent(nodeId, k -> new HashSet<>()).add(shardId);
    }

    /**
     * Removes a shard assignment from a node.
     */
    public void unassignShard(int shardId, String nodeId) {
        Set<String> nodes = shardToNodes.get(shardId);
        if (nodes != null) {
            nodes.remove(nodeId);
            if (nodes.isEmpty()) {
                shardToNodes.remove(shardId);
            }
        }
        Set<Integer> shards = nodeToShards.get(nodeId);
        if (shards != null) {
            shards.remove(shardId);
            if (shards.isEmpty()) {
                nodeToShards.remove(nodeId);
            }
        }
    }

    /**
     * Returns all nodes holding a given shard.
     */
    public Set<String> getNodesForShard(int shardId) {
        Set<String> nodes = shardToNodes.get(shardId);
        return nodes != null ? Collections.unmodifiableSet(nodes) : Collections.emptySet();
    }

    /**
     * Returns all shard IDs assigned to a given node.
     */
    public Set<Integer> getShardsForNode(String nodeId) {
        Set<Integer> shards = nodeToShards.get(nodeId);
        return shards != null ? Collections.unmodifiableSet(shards) : Collections.emptySet();
    }

    /**
     * Removes all shards for a failed node and returns the affected shard IDs.
     */
    public Set<Integer> removeNode(String nodeId) {
        Set<Integer> shards = nodeToShards.remove(nodeId);
        if (shards == null) {
            return Collections.emptySet();
        }
        for (int shardId : shards) {
            Set<String> nodes = shardToNodes.get(shardId);
            if (nodes != null) {
                nodes.remove(nodeId);
                if (nodes.isEmpty()) {
                    shardToNodes.remove(shardId);
                }
            }
        }
        return Collections.unmodifiableSet(shards);
    }

    /**
     * Returns the total number of tracked shards.
     */
    public int shardCount() {
        return shardToNodes.size();
    }

    /**
     * Returns the total number of tracked nodes.
     */
    public int nodeCount() {
        return nodeToShards.size();
    }

    /**
     * Returns the number of shards assigned to a specific node.
     */
    public int shardCountForNode(String nodeId) {
        Set<Integer> shards = nodeToShards.get(nodeId);
        return shards != null ? shards.size() : 0;
    }

    /**
     * Returns all unique shard IDs tracked across the cluster.
     */
    public Set<Integer> getAllShardIds() {
        return Collections.unmodifiableSet(shardToNodes.keySet());
    }
}
