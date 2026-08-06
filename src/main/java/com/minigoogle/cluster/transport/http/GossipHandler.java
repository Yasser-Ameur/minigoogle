package com.minigoogle.cluster.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.GossipProtocol;
import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.ProtocolViolationException;
import com.minigoogle.cluster.transport.dto.GossipExchangeRequest;
import com.minigoogle.cluster.transport.dto.GossipExchangeResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public class GossipHandler implements HttpHandler {
    private final GossipProtocol gossip;
    private final ObjectMapper mapper;
    private final String localNodeId;

    public GossipHandler(GossipProtocol gossip, ObjectMapper mapper, String localNodeId) {
        this.gossip = gossip;
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
            GossipExchangeRequest req = mapper.readValue(exchange.getRequestBody(), GossipExchangeRequest.class);
            ClusterProtocol.validate(req);
            gossip.receiveGossip(req.sourceNodeId(), req.state());

            GossipExchangeResponse resp = new GossipExchangeResponse(
                    ClusterProtocol.PROTOCOL_VERSION,
                    req.requestId(),
                    req.correlationId(),
                    localNodeId,
                    ClusterProtocol.now(),
                    true
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
        byte[] payload = mapper.writeValueAsBytes(java.util.Map.of("error", message));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
    }
}
