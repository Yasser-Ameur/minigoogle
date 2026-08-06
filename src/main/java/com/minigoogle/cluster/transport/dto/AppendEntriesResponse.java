package com.minigoogle.cluster.transport.dto;

import com.minigoogle.cluster.transport.ClusterMessage;

public record AppendEntriesResponse(
        int protocolVersion,
        String requestId,
        String correlationId,
        String sourceNodeId,
        long timestamp,
        int term,
        boolean success
) implements ClusterMessage {
}
