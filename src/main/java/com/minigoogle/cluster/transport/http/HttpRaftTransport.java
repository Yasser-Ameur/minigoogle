package com.minigoogle.cluster.transport.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.transport.ClusterMessage;
import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.cluster.transport.ProtocolViolationException;
import com.minigoogle.cluster.transport.RaftTransport;
import com.minigoogle.cluster.transport.dto.AppendEntriesRequest;
import com.minigoogle.cluster.transport.dto.AppendEntriesResponse;
import com.minigoogle.cluster.transport.dto.InstallSnapshotRequest;
import com.minigoogle.cluster.transport.dto.InstallSnapshotResponse;
import com.minigoogle.cluster.transport.dto.ReadIndexRequest;
import com.minigoogle.cluster.transport.dto.ReadIndexResponse;
import com.minigoogle.cluster.transport.dto.RequestVoteRequest;
import com.minigoogle.cluster.transport.dto.RequestVoteResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class HttpRaftTransport implements RaftTransport {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final NodeDirectory directory;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String localNodeId;
    private final String bearerToken;

    public HttpRaftTransport(NodeDirectory directory, ObjectMapper mapper, String localNodeId, String bearerToken) {
        this.directory = directory;
        this.mapper = mapper;
        this.localNodeId = localNodeId;
        this.bearerToken = bearerToken;
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
    public CompletableFuture<RequestVoteResponse> sendRequestVote(String targetNodeId, RequestVoteRequest request) {
        return sendPost(targetNodeId, "/cluster/v1/raft/request-vote", stampMetadata(request), RequestVoteResponse.class);
    }

    @Override
    public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetNodeId, AppendEntriesRequest request) {
        return sendPost(targetNodeId, "/cluster/v1/raft/append-entries", stampMetadata(request), AppendEntriesResponse.class);
    }

    @Override
    public CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(String targetNodeId, InstallSnapshotRequest request) {
        return sendPost(targetNodeId, "/cluster/v1/raft/install-snapshot", stampMetadata(request), InstallSnapshotResponse.class);
    }

    @Override
    public CompletableFuture<ReadIndexResponse> sendReadIndex(String targetNodeId, ReadIndexRequest request) {
        return sendPost(targetNodeId, "/cluster/v1/raft/read-index", stampMetadata(request), ReadIndexResponse.class);
    }

    private ReadIndexRequest stampMetadata(ReadIndexRequest request) {
        return new ReadIndexRequest(
                ClusterProtocol.PROTOCOL_VERSION,
                ClusterProtocol.newId(),
                request.correlationId(),
                localNodeId,
                ClusterProtocol.now()
        );
    }

    private RequestVoteRequest stampMetadata(RequestVoteRequest request) {
        String correlationId = ClusterProtocol.newId();
        return new RequestVoteRequest(
                ClusterProtocol.PROTOCOL_VERSION,
                ClusterProtocol.newId(),
                correlationId,
                localNodeId,
                ClusterProtocol.now(),
                request.candidateId(),
                request.term(),
                request.lastLogIndex(),
                request.lastLogTerm()
        );
    }

    private AppendEntriesRequest stampMetadata(AppendEntriesRequest request) {
        return new AppendEntriesRequest(
                ClusterProtocol.PROTOCOL_VERSION,
                ClusterProtocol.newId(),
                ClusterProtocol.newId(),
                localNodeId,
                ClusterProtocol.now(),
                request.leaderId(),
                request.term(),
                request.prevLogIndex(),
                request.prevLogTerm(),
                request.entries(),
                request.leaderCommit(),
                request.config() == null ? List.of() : request.config()
        );
    }

    private InstallSnapshotRequest stampMetadata(InstallSnapshotRequest request) {
        return new InstallSnapshotRequest(
                ClusterProtocol.PROTOCOL_VERSION,
                ClusterProtocol.newId(),
                ClusterProtocol.newId(),
                localNodeId,
                ClusterProtocol.now(),
                request.leaderId(),
                request.term(),
                request.lastIncludedIndex(),
                request.lastIncludedTerm(),
                request.data(),
                request.config() == null ? List.of() : request.config()
        );
    }

    private <T extends ClusterMessage> CompletableFuture<T> sendPost(String targetNodeId, String path, ClusterMessage requestObj, Class<T> responseClass) {
        URI baseUri = directory.getBaseUri(targetNodeId);
        if (baseUri == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown node: " + targetNodeId));
        }

        String correlationId = requestObj.correlationId();

        try {
            String payload = mapper.writeValueAsString(requestObj);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(baseUri.resolve(path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header(HttpAuth.AUTHORIZATION, HttpAuth.bearer(bearerToken))
                    .header(HttpAuth.NODE_ID, localNodeId)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("HTTP Error: " + response.statusCode());
                        }
                        try {
                            T parsed = mapper.readValue(response.body(), responseClass);
                            ClusterProtocol.validate(parsed);
                            if (!correlationId.equals(parsed.correlationId())) {
                                throw new ProtocolViolationException(
                                        "Correlation ID mismatch: expected " + correlationId + " but got " + parsed.correlationId());
                            }
                            return parsed;
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException("JSON parse error", e);
                        }
                    });
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
