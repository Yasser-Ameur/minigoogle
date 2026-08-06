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
     * Builds a {@code ClusterSecurity} with a freshly generated random secret.
     *
     * <p>Intended for standalone/single-node setups and tests where the same
     * JVM constructs both the client and the server, so the secret never needs
     * to leave the process. Clusters must instead share one secret across nodes
     * via the {@link #ClusterSecurity(String)} constructor.</p>
     */
    public static ClusterSecurity withRandomSecret() {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        return new ClusterSecurity(uuid.toString());
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
     * Derives the deterministic token for a node from the shared cluster secret.
     *
     * <p>Every node that holds the same cluster secret derives the same token for
     * a given node ID, so peers can authenticate each other without distributing
     * tokens out of band. The value is stable across calls, unlike
     * {@link #generateToken(String)} which mints a fresh token on every call.</p>
     *
     * @param nodeId The node the token belongs to.
     * @return The derived bearer token for the node.
     */
    public String deriveToken(String nodeId) {
        return sha256(clusterSecretKey + ":" + nodeId);
    }

    /**
     * Authenticates a Bearer token against a claimed node identity.
     *
     * <p>The token is accepted if it is the deterministic token derived for the
     * claimed node ID (shared-secret model), or an explicitly registered token
     * from {@link #generateToken(String)}. Deriving on the claimed ID means a
     * node can authenticate a peer it has never met — the bootstrap case for
     * gossip — without relaxing security: forging a claim still requires the
     * shared cluster secret. Comparisons are constant-time.</p>
     *
     * @param authorizationHeader The raw Authorization header value.
     * @param claimedNodeId       The node ID the caller claims to be (from the
     *                            transport's {@code X-Node-Id} header).
     * @return The authenticated node ID, or {@code null} if the token is invalid.
     */
    public String authenticate(String authorizationHeader, String claimedNodeId) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        if (claimedNodeId != null && constantTimeEquals(deriveToken(claimedNodeId), token)) {
            return claimedNodeId;
        }
        for (Map.Entry<String, String> entry : registeredTokens.entrySet()) {
            if (constantTimeEquals(entry.getValue(), token)) {
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

    /**
     * Constant-time string comparison to avoid timing side channels.
     */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
