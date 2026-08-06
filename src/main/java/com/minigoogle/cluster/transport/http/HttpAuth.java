package com.minigoogle.cluster.transport.http;

/**
 * Shared constants for HTTP bearer authentication on the internal RPC
 * transport. Kept in one place so the server-side filter and every client
 * transport produce the exact same header.
 */
public final class HttpAuth {

    public static final String AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    /**
     * Header carrying the source node's claimed identity. The server-side
     * filter derives the expected token from this ID, so a peer can prove its
     * identity even before the receiver has learned it via gossip.
     */
    public static final String NODE_ID = "X-Node-Id";

    private HttpAuth() {
    }

    /**
     * Formats a bearer token into an Authorization header value.
     *
     * @param token The raw token.
     * @return The full header value, e.g. "Bearer abc123".
     */
    public static String bearer(String token) {
        return BEARER_PREFIX + token;
    }
}
