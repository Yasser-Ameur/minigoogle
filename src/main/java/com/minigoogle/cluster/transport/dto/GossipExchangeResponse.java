package com.minigoogle.cluster.transport.dto;

import com.minigoogle.cluster.GossipProtocol.GossipNodeState;
import com.minigoogle.cluster.transport.ClusterMessage;

import java.util.Map;

/**
 * Acknowledgement for a gossip exchange. Echoes the request's
 * {@code requestId} and {@code correlationId} so the caller can match
 * the reply to its request, and carries the responder's own membership
 * table so the exchange is push-pull: the requester merges it back in,
 * converging in one round trip instead of waiting on the responder's
 * next gossip round.
 */
public record GossipExchangeResponse(
        int protocolVersion,
        String requestId,
        String correlationId,
        String sourceNodeId,
        long timestamp,
        boolean accepted,
        Map<String, GossipNodeState> state
) implements ClusterMessage {
}
