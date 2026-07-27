package com.minigoogle.cluster;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cluster security manager for node authentication and encrypted communication.
 *
 * Per ARCHITECTURE.md Ch14:
 *   - Mutual certificates: unauthorized machines cannot join
 *   - Every API requires: Authentication, Authorization, Audit Logging
 *   - Cluster metadata is encrypted
 *   - Sensitive traffic uses TLS
 *
 * Per ARCHITECTURE.md Ch15 §8:
 *   - Bearer token authentication for internal node communication
 *   - Future: OAuth, API Keys, JWT, mutual TLS
 */
public class ClusterSecurity {

    private final String clusterSecretKey;
    private final Map<String, String> registeredTokens = new ConcurrentHashMap<>();

    public ClusterSecurity(String clusterSecretKey) {
        this.clusterSecretKey = clusterSecretKey;
    }

    /**
     * Generates a unique token for a node.
     *
     * @param nodeId The node to generate a token for.
     * @return The generated token.
     */
    public String generateToken(String nodeId) {
        String raw = clusterSecretKey + ":" + nodeId + ":" + System.currentTimeMillis();
        String token = sha256(raw);
        registeredTokens.put(nodeId, token);
        return token;
    }

    /**
     * Validates a node's token.
     *
     * @param nodeId The node ID.
     * @param token  The token presented by the node.
     * @return true if the token is valid.
     */
    public boolean validateToken(String nodeId, String token) {
        String expected = registeredTokens.get(nodeId);
        if (expected == null) return false;
        return expected.equals(token);
    }

    /**
     * Validates a Bearer token from an Authorization header.
     *
     * @param authorizationHeader The full header value.
     * @return The node ID if valid, null otherwise.
     */
    public String validateBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        for (Map.Entry<String, String> entry : registeredTokens.entrySet()) {
            if (entry.getValue().equals(token)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Revokes a node's access.
     */
    public void revokeToken(String nodeId) {
        registeredTokens.remove(nodeId);
    }

    /**
     * Returns true if the node has a valid registered token.
     */
    public boolean isRegistered(String nodeId) {
        return registeredTokens.containsKey(nodeId);
    }

    /**
     * Simple SHA-256 hash for token generation.
     */
    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
