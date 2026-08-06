package com.minigoogle.cluster.transport;

import com.minigoogle.cluster.transport.dto.ShardChunk;

import java.util.concurrent.CompletableFuture;

public interface ShardTransferTransport extends ClusterTransport {
    CompletableFuture<Void> startTransfer(String targetNodeId, String shardId);
    CompletableFuture<Void> transferChunk(String targetNodeId, ShardChunk chunk);
    CompletableFuture<Void> commitTransfer(String targetNodeId, String shardId);
}
