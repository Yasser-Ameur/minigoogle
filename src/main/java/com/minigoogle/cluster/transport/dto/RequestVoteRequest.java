package com.minigoogle.cluster.transport.dto;

import com.minigoogle.cluster.transport.ClusterMessage;

import java.util.List;

public record RequestVoteRequest(
        int protocolVersion,
        String requestId,
        String correlationId,
        String sourceNodeId,
        long timestamp,
        String candidateId,
        int term,
        int lastLogIndex,
        int lastLogTerm
) implements ClusterMessage {
}
