package com.minigoogle.cluster.transport.dto;

import com.minigoogle.cluster.transport.ClusterMessage;

import java.util.List;

public record AppendEntriesRequest(
        int protocolVersion,
        String requestId,
        String correlationId,
        String sourceNodeId,
        long timestamp,
        String leaderId,
        int term,
        int prevLogIndex,
        int prevLogTerm,
        List<byte[]> entries,
        int leaderCommit
) implements ClusterMessage {
}
