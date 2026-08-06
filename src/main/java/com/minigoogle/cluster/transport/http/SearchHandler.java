package com.minigoogle.cluster.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.ProtocolViolationException;
import com.minigoogle.cluster.transport.dto.DispatchQueryRequest;
import com.minigoogle.distributed.query.execution.SearchExecutor;
import com.minigoogle.distributed.query.model.LocalSearchResponse;
import com.minigoogle.distributed.query.model.QueryContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Serves the {@code /cluster/v1/search/dispatch} endpoint: receives a
 * fanned-out query from a coordinator, runs it against the local index via
 * the injected {@link SearchExecutor}, and returns the local Top-K results.
 *
 * <p>The remaining time budget travels with the request so a shard under load
 * cannot outlive the coordinator's scatter deadline. The coordinator's request
 * ID is preserved for distributed tracing.
 */
public class SearchHandler implements HttpHandler {

    private final SearchExecutor localSearch;
    private final ObjectMapper mapper;
    private final String localNodeId;

    public SearchHandler(SearchExecutor localSearch, ObjectMapper mapper, String localNodeId) {
        this.localSearch = localSearch;
        this.mapper = mapper;
        this.localNodeId = localNodeId;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        try {
            DispatchQueryRequest req = mapper.readValue(exchange.getRequestBody(), DispatchQueryRequest.class);
            ClusterProtocol.validate(req);
            if (!AuthFilter.authenticatedSender(exchange, req.sourceNodeId())) {
                sendError(exchange, 403, "Forbidden: source node mismatch");
                return;
            }

            if (localSearch == null) {
                sendError(exchange, 503, "No local search executor configured on this node");
                return;
            }

            QueryContext context = new QueryContext(
                    req.query(),
                    req.topK(),
                    Duration.ofMillis(Math.max(1, req.remainingTimeMs())),
                    UUID.fromString(req.requestId())
            );

            LocalSearchResponse response = localSearch.execute(context);
            sendResponse(exchange, response);
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
