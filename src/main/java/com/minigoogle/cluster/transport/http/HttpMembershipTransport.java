package com.minigoogle.cluster.transport.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.GossipProtocol.GossipNodeState;
import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.MembershipTransport;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.cluster.transport.ProtocolViolationException;
import com.minigoogle.cluster.transport.dto.GossipExchangeRequest;
import com.minigoogle.cluster.transport.dto.GossipExchangeResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class HttpMembershipTransport implements MembershipTransport {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final NodeDirectory directory;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String localNodeId;

    public HttpMembershipTransport(NodeDirectory directory, ObjectMapper mapper, String localNodeId) {
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
    public CompletableFuture<Void> exchangeState(String targetNodeId, Map<String, GossipNodeState> state) {
        URI baseUri = directory.getBaseUri(targetNodeId);
        if (baseUri == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown node: " + targetNodeId));
        }

        String correlationId = ClusterProtocol.newId();
        GossipExchangeRequest req = new GossipExchangeRequest(
                ClusterProtocol.PROTOCOL_VERSION,
                ClusterProtocol.newId(),
                correlationId,
                localNodeId,
                ClusterProtocol.now(),
                state
        );

        try {
            String payload = mapper.writeValueAsString(req);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(baseUri.resolve("/cluster/v1/gossip/exchange"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("HTTP Error: " + response.statusCode());
                        }
                        try {
                            GossipExchangeResponse ack = mapper.readValue(response.body(), GossipExchangeResponse.class);
                            ClusterProtocol.validate(ack);
                            if (!correlationId.equals(ack.correlationId())) {
                                throw new ProtocolViolationException(
                                        "Correlation ID mismatch: expected " + correlationId + " but got " + ack.correlationId());
                            }
                            return null;
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException("JSON parse error", e);
                        }
                    });
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
