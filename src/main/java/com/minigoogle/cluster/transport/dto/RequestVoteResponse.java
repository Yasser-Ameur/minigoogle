package com.minigoogle.cluster.transport.dto;

import com.minigoogle.cluster.transport.ClusterMessage;

public record RequestVoteResponse(
        int protocolVersion,
        String requestId,
        String correlationId,
        String sourceNodeId,
        long timestamp,
        int term,
        boolean voteGranted
) implements ClusterMessage {
}
