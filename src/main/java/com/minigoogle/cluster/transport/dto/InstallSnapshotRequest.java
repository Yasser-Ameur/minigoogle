package com.minigoogle.cluster.transport.dto;

import com.minigoogle.cluster.transport.ClusterMessage;

import java.util.List;

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
        byte[] data,
        List<String> config
) implements ClusterMessage {

    /**
     * Creates an InstallSnapshot request without a committed configuration.
     * Old senders (pre-membership-reconfiguration) and tests use this; the
     * receiver treats a missing config as "no config carried".
     */
    public InstallSnapshotRequest(
            int protocolVersion,
            String requestId,
            String correlationId,
            String sourceNodeId,
            long timestamp,
            String leaderId,
            int term,
            int lastIncludedIndex,
            int lastIncludedTerm,
            byte[] data) {
        this(protocolVersion, requestId, correlationId, sourceNodeId, timestamp, leaderId, term,
                lastIncludedIndex, lastIncludedTerm, data, List.of());
    }
}
