package com.minigoogle.cluster.placement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.cluster.transport.http.HttpAuth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Posts an {@link IngestedDocument} to a peer's
 * {@code /cluster/v1/documents/ingest} endpoint, by node id resolved through
 * {@link NodeDirectory}, the same way {@link
 * com.minigoogle.cluster.transport.http.HttpSearchTransport} dispatches
 * queries.
 */
public class HttpIngestTransport {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final NodeDirectory directory;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String localNodeId;
    private final String bearerToken;

    public HttpIngestTransport(NodeDirectory directory, ObjectMapper mapper, String localNodeId, String bearerToken) {
        this.directory = directory;
        this.mapper = IngestJson.mapper(mapper);
        this.localNodeId = localNodeId;
        this.bearerToken = bearerToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * @param targetNodeId The node to deliver the document to.
     * @param doc          The document to deliver.
     * @return A future completing with {@code true} if the target newly
     *         indexed the document, {@code false} if it already had it.
     */
    public CompletableFuture<Boolean> ingest(String targetNodeId, IngestedDocument doc) {
        URI baseUri = directory.getBaseUri(targetNodeId);
        if (baseUri == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown node: " + targetNodeId));
        }

        IngestRequest request = new IngestRequest(
                ClusterProtocol.PROTOCOL_VERSION,
                ClusterProtocol.newId(),
                ClusterProtocol.newId(),
                localNodeId,
                ClusterProtocol.now(),
                doc
        );

        try {
            String payload = mapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(baseUri.resolve("/cluster/v1/documents/ingest"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header(HttpAuth.AUTHORIZATION, HttpAuth.bearer(bearerToken))
                    .header(HttpAuth.NODE_ID, localNodeId)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("HTTP Error: " + response.statusCode());
                        }
                        try {
                            return mapper.readValue(response.body(), IngestResponse.class).ingested();
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException("JSON parse error", e);
                        }
                    });
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
