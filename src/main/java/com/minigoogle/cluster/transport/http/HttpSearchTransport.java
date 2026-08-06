package com.minigoogle.cluster.transport.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.cluster.transport.SearchTransport;
import com.minigoogle.cluster.transport.dto.DispatchQueryRequest;
import com.minigoogle.distributed.query.model.LocalSearchResponse;
import com.minigoogle.distributed.query.model.QueryContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP implementation of {@link SearchTransport}.
 *
 * <p>Serializes the query as a {@link DispatchQueryRequest} carrying the
 * coordinator's remaining time budget, posts it to the target node's
 * {@code /cluster/v1/search/dispatch} endpoint, and deserializes the local
 * Top-K results back into a {@link LocalSearchResponse}.
 */
public class HttpSearchTransport implements SearchTransport {
    private final NodeDirectory directory;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String localNodeId;

    public HttpSearchTransport(NodeDirectory directory, ObjectMapper mapper, String localNodeId) {
        this.directory = directory;
        this.mapper = mapper;
        this.localNodeId = localNodeId;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public CompletableFuture<LocalSearchResponse> dispatchQuery(String targetNodeId, QueryContext queryContext) {
        URI baseUri = directory.getBaseUri(targetNodeId);
        if (baseUri == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown node: " + targetNodeId));
        }

        long remainingTimeMs = Math.max(1, queryContext.getRemainingTimeMs());
        DispatchQueryRequest request = new DispatchQueryRequest(
                ClusterProtocol.PROTOCOL_VERSION,
                queryContext.getRequestId().toString(),
                ClusterProtocol.newId(),
                localNodeId,
                ClusterProtocol.now(),
                queryContext.getQuery(),
                queryContext.getTopK(),
                remainingTimeMs
        );

        try {
            String payload = mapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(baseUri.resolve("/cluster/v1/search/dispatch"))
                    .timeout(Duration.ofMillis(Math.min(remainingTimeMs, 5000)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("HTTP Error: " + response.statusCode());
                        }
                        try {
                            return mapper.readValue(response.body(), LocalSearchResponse.class);
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException("JSON parse error", e);
                        }
                    });
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
