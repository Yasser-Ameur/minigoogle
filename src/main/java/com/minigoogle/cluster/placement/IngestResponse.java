package com.minigoogle.cluster.placement;

import com.minigoogle.cluster.transport.ClusterMessage;

/**
 * Wire response for {@link IngestRequest}: whether the document was newly
 * indexed by the receiving node ({@code false} means it was already present,
 * which is not a failure).
 */
public record IngestResponse(
        int protocolVersion,
        String requestId,
        String correlationId,
        String sourceNodeId,
        long timestamp,
        boolean ingested
) implements ClusterMessage {
}
