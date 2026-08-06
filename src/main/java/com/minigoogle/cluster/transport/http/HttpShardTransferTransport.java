package com.minigoogle.cluster.transport.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.cluster.transport.ShardTransferTransport;
import com.minigoogle.cluster.transport.dto.ShardChunk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class HttpShardTransferTransport implements ShardTransferTransport {
    private final NodeDirectory directory;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String localNodeId;

    public HttpShardTransferTransport(NodeDirectory directory, ObjectMapper mapper, String localNodeId) {
        this.directory = directory;
        this.mapper = mapper;
        this.localNodeId = localNodeId;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public CompletableFuture<Void> startTransfer(String targetNodeId, String shardId) {
        return sendPost(targetNodeId, "/cluster/v1/shards/" + shardId + "/transfer/start", "{}");
    }

    @Override
    public CompletableFuture<Void> transferChunk(String targetNodeId, ShardChunk chunk) {
        return sendPost(targetNodeId, "/cluster/v1/shards/" + chunk.shardId() + "/transfer/chunk", stampMetadata(chunk));
    }

    @Override
    public CompletableFuture<Void> commitTransfer(String targetNodeId, String shardId) {
        return sendPost(targetNodeId, "/cluster/v1/shards/" + shardId + "/transfer/commit", "{}");
    }

    private ShardChunk stampMetadata(ShardChunk chunk) {
        return new ShardChunk(
                ClusterProtocol.PROTOCOL_VERSION,
                ClusterProtocol.newId(),
                ClusterProtocol.newId(),
                localNodeId,
                ClusterProtocol.now(),
                chunk.shardId(),
                chunk.offset(),
                chunk.data(),
                chunk.checksum()
        );
    }

    private CompletableFuture<Void> sendPost(String targetNodeId, String path, Object requestObj) {
        URI baseUri = directory.getBaseUri(targetNodeId);
        if (baseUri == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown node: " + targetNodeId));
        }

        try {
            String payload = requestObj instanceof String ? (String) requestObj : mapper.writeValueAsString(requestObj);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(baseUri.resolve(path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("HTTP Error: " + response.statusCode());
                        }
                        return null;
                    });
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
