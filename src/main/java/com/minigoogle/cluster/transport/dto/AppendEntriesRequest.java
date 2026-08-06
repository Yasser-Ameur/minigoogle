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
        int leaderCommit,
        List<String> config
) implements ClusterMessage {

    /**
     * Creates an AppendEntries request without a committed configuration.
     * Old senders (pre-membership-reconfiguration) and tests use this; the
     * receiver treats a missing config as "no config carried".
     */
    public AppendEntriesRequest(
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
            int leaderCommit) {
        this(protocolVersion, requestId, correlationId, sourceNodeId, timestamp, leaderId, term,
                prevLogIndex, prevLogTerm, entries, leaderCommit, List.of());
    }
}
