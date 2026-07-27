package com.minigoogle.monitoring.health;

import com.minigoogle.network.http.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Performs health checks on cluster nodes.
 *
 * Per ARCHITECTURE.md Ch11:
 *   Health checks verify that every service is responsive.
 *   Unhealthy nodes are detected and removed from routing.
 */
public class HealthChecker {

    private final RestClient restClient;
    private final Map<String, HealthStatus> lastResults = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public HealthChecker(RestClient restClient, long timeoutMs) {
        this.restClient = restClient;
        this.timeoutMs = timeoutMs;
    }

    public HealthChecker(RestClient restClient) {
        this(restClient, 5000);
    }

    /**
     * Checks the health of a single node.
     *
     * @param nodeId   The node to check.
     * @param hostUrl  The node's base URL (e.g. "http://host:port").
     * @return The health status.
     */
    public HealthStatus check(String nodeId, String hostUrl) {
        long start = System.currentTimeMillis();
        try {
            String response = restClient.get(hostUrl + "/api/v1/health");
            long elapsed = System.currentTimeMillis() - start;
            HealthStatus status = HealthStatus.healthy(nodeId, elapsed);
            lastResults.put(nodeId, status);
            return status;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > timeoutMs) {
                HealthStatus degraded = HealthStatus.degraded(nodeId, elapsed, "Slow response: " + elapsed + "ms");
                lastResults.put(nodeId, degraded);
                return degraded;
            }
            HealthStatus unhealthy = HealthStatus.unhealthy(nodeId, "Unreachable: " + e.getMessage());
            lastResults.put(nodeId, unhealthy);
            return unhealthy;
        }
    }

    /**
     * Returns the last known health status for a node.
     */
    public HealthStatus getLastResult(String nodeId) {
        return lastResults.get(nodeId);
    }

    /**
     * Returns the last known health status for all checked nodes.
     */
    public Map<String, HealthStatus> getAllResults() {
        return Map.copyOf(lastResults);
    }
}
