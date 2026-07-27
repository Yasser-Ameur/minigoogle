package com.minigoogle.monitoring.health;

/**
 * Health status of a single node or service.
 *
 * Per ARCHITECTURE.md Ch11:
 *   Health checks verify that every service is responsive
 *   and functioning correctly.
 */
public record HealthStatus(
        String nodeId,
        Status status,
        long responseTimeMs,
        String message,
        long timestamp
) {
    public enum Status {
        HEALTHY,
        DEGRADED,
        UNHEALTHY
    }

    public static HealthStatus healthy(String nodeId, long responseTimeMs) {
        return new HealthStatus(nodeId, Status.HEALTHY, responseTimeMs, "OK", System.currentTimeMillis());
    }

    public static HealthStatus degraded(String nodeId, long responseTimeMs, String message) {
        return new HealthStatus(nodeId, Status.DEGRADED, responseTimeMs, message, System.currentTimeMillis());
    }

    public static HealthStatus unhealthy(String nodeId, String message) {
        return new HealthStatus(nodeId, Status.UNHEALTHY, -1, message, System.currentTimeMillis());
    }

    public boolean isHealthy() {
        return status == Status.HEALTHY;
    }
}
