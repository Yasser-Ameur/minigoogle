package com.minigoogle.cluster.transport.dto;

import com.minigoogle.cluster.transport.ClusterMessage;

public record InstallSnapshotRequest(
        int protocolVersion,
        String requestId,
        String correlationId,
        String sourceNodeId,
        long timestamp,
        String leaderId,
        int term,
        int lastIncludedIndex,
        int lastIncludedTerm,
        byte[] data
) implements ClusterMessage {
}
