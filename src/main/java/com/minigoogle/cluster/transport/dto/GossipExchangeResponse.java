package com.minigoogle.cluster.transport.dto;

import com.minigoogle.cluster.transport.ClusterMessage;

/**
 * Acknowledgement for a gossip exchange. Echoes the request's
 * {@code requestId} and {@code correlationId} so the caller can match
 * the reply to its request.
 */
public record GossipExchangeResponse(
        int protocolVersion,
        String requestId,
        String correlationId,
        String sourceNodeId,
        long timestamp,
        boolean accepted
) implements ClusterMessage {
}
