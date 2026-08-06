package com.minigoogle.cluster.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.RaftConsensus;
import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.ProtocolViolationException;
import com.minigoogle.cluster.transport.dto.AppendEntriesRequest;
import com.minigoogle.cluster.transport.dto.AppendEntriesResponse;
import com.minigoogle.cluster.transport.dto.RequestVoteRequest;
import com.minigoogle.cluster.transport.dto.RequestVoteResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

public class RaftHandler implements HttpHandler {
    private final RaftConsensus raft;
    private final ObjectMapper mapper;
    private final String localNodeId;

    public RaftHandler(RaftConsensus raft, ObjectMapper mapper, String localNodeId) {
        this.raft = raft;
        this.mapper = mapper;
        this.localNodeId = localNodeId;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try {
            String path = exchange.getRequestURI().getPath();

            if (path.endsWith("/request-vote")) {
                RequestVoteRequest req = mapper.readValue(exchange.getRequestBody(), RequestVoteRequest.class);
                ClusterProtocol.validate(req);
                if (!AuthFilter.authenticatedSender(exchange, req.sourceNodeId())) {
                    sendError(exchange, 403, "Forbidden: source node mismatch");
                    return;
                }
                boolean granted = raft.receiveVoteRequest(req.candidateId(), req.term());
                RequestVoteResponse resp = new RequestVoteResponse(
                        ClusterProtocol.PROTOCOL_VERSION,
                        req.requestId(),
                        req.correlationId(),
                        localNodeId,
                        ClusterProtocol.now(),
                        raft.getCurrentTerm(),
                        granted
                );
                sendResponse(exchange, resp);
            } else if (path.endsWith("/append-entries")) {
                AppendEntriesRequest req = mapper.readValue(exchange.getRequestBody(), AppendEntriesRequest.class);
                ClusterProtocol.validate(req);
                if (!AuthFilter.authenticatedSender(exchange, req.sourceNodeId())) {
                    sendError(exchange, 403, "Forbidden: source node mismatch");
                    return;
                }
                raft.receiveHeartbeat(req.leaderId(), req.term());
                AppendEntriesResponse resp = new AppendEntriesResponse(
                        ClusterProtocol.PROTOCOL_VERSION,
                        req.requestId(),
                        req.correlationId(),
                        localNodeId,
                        ClusterProtocol.now(),
                        raft.getCurrentTerm(),
                        true
                );
                sendResponse(exchange, resp);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        } catch (ProtocolViolationException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    private void sendResponse(HttpExchange exchange, Object responseObj) throws IOException {
        byte[] payload = mapper.writeValueAsBytes(responseObj);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        byte[] payload = mapper.writeValueAsBytes(Map.of("error", message));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
    }
}
