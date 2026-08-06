package com.minigoogle.cluster.transport.http;

import com.minigoogle.cluster.ClusterSecurity;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Authenticates every internal RPC before it reaches a handler.
 *
 * <p>Runs as a {@link Filter} on every protected {@code /cluster/v1/*} context.
 * Reads the {@code Authorization: Bearer <token>} header and the
 * {@code X-Node-Id} claim, validates the token against {@link ClusterSecurity},
 * and rejects invalid requests with 401 before any handler logic executes. On
 * success the authenticated node ID is recorded as an exchange attribute so
 * handlers can bind the envelope's {@code sourceNodeId} to the caller's
 * identity.</p>
 */
public class AuthFilter extends Filter {

    public static final String AUTHENTICATED_NODE_ID = "authenticatedNodeId";

    private final ClusterSecurity security;

    public AuthFilter(ClusterSecurity security) {
        this.security = security;
    }

    @Override
    public String description() {
        return "Bearer token authentication";
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst(HttpAuth.AUTHORIZATION);
        String claimedNodeId = exchange.getRequestHeaders().getFirst(HttpAuth.NODE_ID);
        String authenticatedNodeId = security.authenticate(authorization, claimedNodeId);
        if (authenticatedNodeId == null) {
            byte[] body = "{\"error\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        exchange.setAttribute(AUTHENTICATED_NODE_ID, authenticatedNodeId);
        chain.doFilter(exchange);
    }

    /**
     * @return The node ID authenticated by the filter, or null if the request
     *         bypassed the filter (e.g. a raw context in a test).
     */
    public static String authenticatedNodeId(HttpExchange exchange) {
        return (String) exchange.getAttribute(AUTHENTICATED_NODE_ID);
    }

    /**
     * Binds the envelope's claimed source node to the authenticated caller.
     *
     * @param exchange            The exchange being handled.
     * @param claimedSourceNodeId The {@code sourceNodeId} carried on the wire.
     * @return true if the claim matches the authenticated node ID.
     */
    public static boolean authenticatedSender(HttpExchange exchange, String claimedSourceNodeId) {
        String authenticated = authenticatedNodeId(exchange);
        return claimedSourceNodeId != null && claimedSourceNodeId.equals(authenticated);
    }
}
