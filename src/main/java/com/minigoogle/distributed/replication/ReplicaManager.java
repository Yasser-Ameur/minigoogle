package com.minigoogle.distributed.replication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cluster-level replica assignment manager.
 *
 * Every shard has exactly 3 copies: one primary and two replicas.
 * This class tracks which nodes hold which replicas and handles
 * leader promotion when a primary fails.
 *
 * Per-node replication state is managed by
 * {@code storage.replication.ReplicationManager}.
 */
public class ReplicaManager {

    private final Map<Integer, ShardAssignment> assignments = new ConcurrentHashMap<>();

    /**
     * Assigns a primary and two replicas for a shard.
     */
    public void assignReplicas(int shardId, String primaryNodeId, List<String> replicaNodeIds) {
        assignments.put(shardId, new ShardAssignment(shardId, primaryNodeId,
                Collections.unmodifiableList(new ArrayList<>(replicaNodeIds))));
    }

    /**
     * Returns the full assignment for a shard, or null if unassigned.
     */
    public ShardAssignment getAssignment(int shardId) {
        return assignments.get(shardId);
    }

    /**
     * Returns the primary node for a shard, or null if unassigned.
     */
    public String getPrimary(int shardId) {
        ShardAssignment a = assignments.get(shardId);
        return a != null ? a.primaryNodeId() : null;
    }

    /**
     * Returns the list of replica node IDs for a shard.
     */
    public List<String> getReplicas(int shardId) {
        ShardAssignment a = assignments.get(shardId);
        return a != null ? a.replicaNodeIds() : Collections.emptyList();
    }

    /**
     * Promotes the first replica to primary and removes it from the replica list.
     * The old primary is dropped.
     *
     * @return true if a replica was available for promotion, false otherwise.
     */
    public boolean promoteReplica(int shardId) {
        ShardAssignment a = assignments.get(shardId);
        if (a == null || a.replicaNodeIds().isEmpty()) {
            return false;
        }
        String newPrimary = a.replicaNodeIds().get(0);
        List<String> remainingReplicas = a.replicaNodeIds().subList(1, a.replicaNodeIds().size());
        assignments.put(shardId, new ShardAssignment(shardId, newPrimary,
                Collections.unmodifiableList(new ArrayList<>(remainingReplicas))));
        return true;
    }

    /**
     * Removes a failed node from all replica lists.
     * If the node was primary, promotes the first available replica.
     *
     * @return The list of shard IDs that were affected.
     */
    public List<Integer> removeNode(String nodeId) {
        List<Integer> affected = new ArrayList<>();
        for (Map.Entry<Integer, ShardAssignment> entry : assignments.entrySet()) {
            ShardAssignment a = entry.getValue();
            if (nodeId.equals(a.primaryNodeId())) {
                promoteReplica(entry.getKey());
                affected.add(entry.getKey());
            } else if (a.replicaNodeIds().contains(nodeId)) {
                List<String> newReplicas = new ArrayList<>(a.replicaNodeIds());
                newReplicas.remove(nodeId);
                assignments.put(entry.getKey(), new ShardAssignment(
                        entry.getKey(), a.primaryNodeId(),
                        Collections.unmodifiableList(newReplicas)));
                affected.add(entry.getKey());
            }
        }
        return affected;
    }

    /**
     * Returns all shard IDs that have assignments.
     */
    public java.util.Set<Integer> getAllAssignedShardIds() {
        return Collections.unmodifiableSet(assignments.keySet());
    }

    /**
     * Returns the total number of shard assignments.
     */
    public int size() {
        return assignments.size();
    }

    /**
     * Snapshot of replica placement for a single shard.
     */
    public record ShardAssignment(
            int shardId,
            String primaryNodeId,
            List<String> replicaNodeIds
    ) {
    }
}
