package com.minigoogle.monitoring.dashboard;

import com.minigoogle.monitoring.health.HealthStatus;
import com.minigoogle.monitoring.metrics.MetricRegistry;

import java.util.Map;

/**
 * Aggregated cluster dashboard view.
 *
 * Per ARCHITECTURE.md Ch11:
 *   Dashboard shows live metrics for all nodes.
 *   Provides a single-pane view of cluster health and performance.
 *
 * Per ARCHITECTURE.md Ch15 §19:
 *   Monitoring dashboard updates live.
 *   Metrics visible live during search.
 */
public class ClusterDashboard {

    private final MetricRegistry metricRegistry;
    private final Map<String, HealthStatus> healthStatuses;
    private final int totalShards;

    public ClusterDashboard(MetricRegistry metricRegistry,
                            Map<String, HealthStatus> healthStatuses,
                            int totalShards) {
        this.metricRegistry = metricRegistry;
        this.healthStatuses = healthStatuses;
        this.totalShards = totalShards;
    }

    /**
     * Returns the current cluster overview as a formatted string.
     */
    public String renderOverview() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Cluster Dashboard ===\n");

        // Health summary
        long healthy = healthStatuses.values().stream()
                .filter(h -> h.status() == HealthStatus.Status.HEALTHY).count();
        long degraded = healthStatuses.values().stream()
                .filter(h -> h.status() == HealthStatus.Status.DEGRADED).count();
        long unhealthy = healthStatuses.values().stream()
                .filter(h -> h.status() == HealthStatus.Status.UNHEALTHY).count();

        sb.append(String.format("Nodes: %d total, %d healthy, %d degraded, %d unhealthy\n",
                healthStatuses.size(), healthy, degraded, unhealthy));
        sb.append(String.format("Shards: %d\n", totalShards));

        // Key metrics
        Map<String, Double> snapshot = metricRegistry.getSnapshot();
        sb.append("\n--- Key Metrics ---\n");
        for (var entry : snapshot.entrySet()) {
            sb.append(String.format("  %s: %.2f\n", entry.getKey(), entry.getValue()));
        }

        return sb.toString();
    }

    /**
     * Returns metrics for a specific node.
     */
    public Map<String, Double> getNodeMetrics(String nodeId) {
        // In a full implementation, this would filter metrics by node ID
        return metricRegistry.getSnapshot();
    }

    /**
     * Returns the health status of a specific node.
     */
    public HealthStatus getNodeHealth(String nodeId) {
        return healthStatuses.get(nodeId);
    }

    /**
     * Returns the total number of nodes in the dashboard.
     */
    public int getNodeCount() {
        return healthStatuses.size();
    }

    /**
     * Returns the number of healthy nodes.
     */
    public int getHealthyNodeCount() {
        return (int) healthStatuses.values().stream()
                .filter(HealthStatus::isHealthy).count();
    }
}
