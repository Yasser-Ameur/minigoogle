package com.minigoogle.cluster.transport.dto;

import com.minigoogle.cluster.transport.ClusterMessage;

public record ShardChunk(
        int protocolVersion,
        String requestId,
        String correlationId,
        String sourceNodeId,
        long timestamp,
        String shardId,
        long offset,
        byte[] data,
        String checksum
) implements ClusterMessage {
}
