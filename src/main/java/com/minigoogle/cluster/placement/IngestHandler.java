package com.minigoogle.cluster.placement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.ProtocolViolationException;
import com.minigoogle.cluster.transport.http.AuthFilter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

/**
 * Serves the {@code /cluster/v1/documents/ingest} endpoint: accepts a
 * document placed or repaired onto this node by a peer and hands it to the
 * local {@link DocumentIngest}.
 *
 * <p>Mirrors {@link com.minigoogle.cluster.transport.http.SearchHandler}: when
 * no {@link DocumentIngest} is configured (placement disabled on this node)
 * the endpoint is still registered but answers 503, the same way
 * {@code SearchHandler} does for a {@code null} {@code SearchExecutor}.
 */
public class IngestHandler implements HttpHandler {

    private final DocumentIngest ingest;
    private final ObjectMapper mapper;
    private final String localNodeId;

    public IngestHandler(DocumentIngest ingest, ObjectMapper mapper, String localNodeId) {
        this.ingest = ingest;
        this.mapper = IngestJson.mapper(mapper);
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
            IngestRequest req = mapper.readValue(exchange.getRequestBody(), IngestRequest.class);
            ClusterProtocol.validate(req);
            if (!AuthFilter.authenticatedSender(exchange, req.sourceNodeId())) {
                sendError(exchange, 403, "Forbidden: source node mismatch");
                return;
            }

            if (ingest == null) {
                sendError(exchange, 503, "No document ingest configured on this node");
                return;
            }

            boolean ingested = ingest.ingest(req.document());
            IngestResponse resp = new IngestResponse(
                    ClusterProtocol.PROTOCOL_VERSION,
                    req.requestId(),
                    req.correlationId(),
                    localNodeId,
                    ClusterProtocol.now(),
                    ingested
            );
            sendResponse(exchange, resp);
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
