package com.minigoogle.cluster.placement;

import com.minigoogle.cluster.transport.ClusterMessage;

/**
 * Wire request for delivering a document to a peer that owns it on the ring:
 * a direct placement from the crawling node, or a repair after a membership
 * change.
 */
public record IngestRequest(
        int protocolVersion,
        String requestId,
        String correlationId,
        String sourceNodeId,
        long timestamp,
        IngestedDocument document
) implements ClusterMessage {
}
