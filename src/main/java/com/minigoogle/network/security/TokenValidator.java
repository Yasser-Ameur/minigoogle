package com.minigoogle.network.security;

/**
 * Validates Bearer tokens from inter-node communication.
 *
 * Per ARCHITECTURE.md section 18 (Internal Security):
 * Every node receives a cluster token during startup.
 * Every request includes an Authorization header with the Bearer token.
 * The coordinator validates the token; unauthorized nodes cannot join.
 */
public class TokenValidator {

    private final String clusterToken;

    /**
     * @param clusterToken The shared secret token for the cluster.
     */
    public TokenValidator(String clusterToken) {
        if (clusterToken == null || clusterToken.isEmpty()) {
            throw new IllegalArgumentException("Cluster token must not be null or empty");
        }
        this.clusterToken = clusterToken;
    }

    /**
     * Validates the Authorization header from an incoming request.
     *
     * @param authorizationHeader The raw value of the Authorization header,
     *                            e.g. "Bearer abc123".
     * @return true if the token matches, false otherwise.
     */
    public boolean validate(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isEmpty()) {
            return false;
        }
        if (!authorizationHeader.startsWith("Bearer ")) {
            return false;
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        return clusterToken.equals(token);
    }
}
