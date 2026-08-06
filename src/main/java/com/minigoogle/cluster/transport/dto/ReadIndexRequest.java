package com.minigoogle.cluster.transport.dto;

import com.minigoogle.cluster.transport.ClusterMessage;

/**
 * A follower's request for the leader's current read index: the commit index
 * the leader would safely serve a linearizable read at. The leader confirms it
 * is still the leader (read barrier) before answering.
 */
public record ReadIndexRequest(
        int protocolVersion,
        String requestId,
        String correlationId,
        String sourceNodeId,
        long timestamp
) implements ClusterMessage {
}
