package com.minigoogle.cluster.transport.dto;

import com.minigoogle.cluster.transport.ClusterMessage;

/**
 * The leader's reply to a {@link ReadIndexRequest}. When {@code success} is
 * true, {@code commitIndex} is a linearizable read index in {@code term}: the
 * follower may serve a read once it has applied through that index. When false,
 * the leader could not establish a barrier (no quorum) or is no longer leader.
 */
public record ReadIndexResponse(
        int protocolVersion,
        String requestId,
        String correlationId,
        String sourceNodeId,
        long timestamp,
        int term,
        int commitIndex,
        boolean success
) implements ClusterMessage {
}
