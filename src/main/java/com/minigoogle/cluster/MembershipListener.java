package com.minigoogle.cluster;

/**
 * Callback interface for reacting to cluster membership changes
 * detected by the gossip protocol.
 *
 * This decouples the gossip layer from consumers of membership events
 * (e.g., the hash ring, shard manager, Raft voter list).
 */
public interface MembershipListener {

    /**
     * Called when a new node is discovered in the cluster.
     *
     * @param nodeId The ID of the newly discovered node.
     */
    void onNodeJoined(String nodeId);

    /**
     * Called when a node is confirmed dead and removed from the cluster.
     *
     * @param nodeId The ID of the departed node.
     */
    void onNodeLeft(String nodeId);
}
