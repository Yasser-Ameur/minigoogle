package com.minigoogle.distributed.model;

/**
 * Record representing a node in the cluster with host, port, role, status, and last heartbeat timestamp.
 */
public record NodeInfo(
        String nodeId,
        String host,
        int port,
        NodeRole role,
        NodeStatus status,
        long lastHeartbeat
) {
    public NodeInfo withStatus(NodeStatus newStatus) {
        return new NodeInfo(nodeId, host, port, role, newStatus, lastHeartbeat);
    }

    public NodeInfo withHeartbeat(long newHeartbeat) {
        return new NodeInfo(nodeId, host, port, role, status, newHeartbeat);
    }
}
