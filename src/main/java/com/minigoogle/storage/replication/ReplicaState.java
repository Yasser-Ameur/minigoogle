package com.minigoogle.storage.replication;

/**
 * Represents the role of a shard on a specific node.
 */
public enum ReplicaState {
    /**
     * This node is the leader for the shard. Receives all writes.
     */
    LEADER,

    /**
     * This node is a follower. Replicates writes from the leader.
     */
    FOLLOWER,

    /**
     * The shard is not assigned to this node, or is currently being recovered.
     */
    UNASSIGNED
}
