package com.minigoogle.cluster.transport.dto;

import com.minigoogle.cluster.transport.ClusterMessage;

/**
 * Wire request for fanning a search query out to a remote shard node.
 *
 * <p>Carries only the query essentials — text, top-K, and the coordinator's
 * remaining time budget — so the receiving node rebuilds its own
 * {@link com.minigoogle.distributed.query.model.QueryContext} with a fresh
 * start time but the caller's deadline preserved.
 */
public record DispatchQueryRequest(
        int protocolVersion,
        String requestId,
        String correlationId,
        String sourceNodeId,
        long timestamp,
        String query,
        int topK,
        long remainingTimeMs
) implements ClusterMessage {
}
