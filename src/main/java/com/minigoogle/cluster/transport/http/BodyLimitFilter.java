package com.minigoogle.cluster.transport.http;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * Caps the request body on internal cluster routes. Every internal transport
 * sends a fixed-length body, so a request that declares no length is refused
 * with 411 and one that declares more than the cap is refused with 413 before
 * a byte of it is read; the public REST server enforces the same idea through
 * {@code server.maxBodyBytes}, and without this the ingest route, whose
 * legitimate payload is a whole page of text, would let any holder of the
 * cluster secret allocate without bound.
 */
public final class BodyLimitFilter extends Filter {

    /** Generous for a parsed page of text, small against the heap. */
    public static final long DEFAULT_MAX_BODY_BYTES = 8L * 1024 * 1024;

    private final long maxBodyBytes;

    public BodyLimitFilter(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        String method = exchange.getRequestMethod();
        if ("POST".equals(method) || "PUT".equals(method)) {
            String declared = exchange.getRequestHeaders().getFirst("Content-Length");
            if (declared == null) {
                reject(exchange, 411);
                return;
            }
            long length;
            try {
                length = Long.parseLong(declared.trim());
            } catch (NumberFormatException e) {
                reject(exchange, 400);
                return;
            }
            if (length > maxBodyBytes) {
                reject(exchange, 413);
                return;
            }
        }
        chain.doFilter(exchange);
    }

    private static void reject(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    @Override
    public String description() {
        return "Rejects internal RPC bodies above " + maxBodyBytes + " bytes";
    }
}
