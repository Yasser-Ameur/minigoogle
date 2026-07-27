package com.minigoogle.storage.replication;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the replication state (leader, follower, unassigned) of all shards on this node.
 */
public class ReplicationManager {

    private final Map<Integer, ReplicaState> states = new ConcurrentHashMap<>();

    /**
     * Sets the state for a specific shard.
     */
    public void setState(int shardId, ReplicaState state) {
        states.put(shardId, state);
    }

    /**
     * Retrieves the current state of a shard on this node.
     * @return The state, or UNASSIGNED if unknown.
     */
    public ReplicaState getState(int shardId) {
        return states.getOrDefault(shardId, ReplicaState.UNASSIGNED);
    }

    /**
     * @return true if this node is the leader for the given shard.
     */
    public boolean isLeader(int shardId) {
        return getState(shardId) == ReplicaState.LEADER;
    }
}
