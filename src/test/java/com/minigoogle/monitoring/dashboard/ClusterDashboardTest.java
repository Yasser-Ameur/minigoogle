package com.minigoogle.monitoring.dashboard;

import com.minigoogle.monitoring.health.HealthStatus;
import com.minigoogle.monitoring.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for ClusterDashboard functionality. */
class ClusterDashboardTest {

    @Test
    void testRenderOverviewContainsHeader() {
        MetricRegistry registry = new MetricRegistry();
        registry.record("qps", 100.0);
        Map<String, HealthStatus> health = Map.of(
                "node-1", HealthStatus.healthy("node-1", 5),
                "node-2", HealthStatus.unhealthy("node-2", "down")
        );
        ClusterDashboard dashboard = new ClusterDashboard(registry, health, 8);

        String overview = dashboard.renderOverview();
        assertTrue(overview.contains("Cluster Dashboard"));
        assertTrue(overview.contains("1 healthy"));
        assertTrue(overview.contains("1 unhealthy"));
        assertTrue(overview.contains("Shards: 8"));
        assertTrue(overview.contains("qps"));
    }

    @Test
    void testGetNodeHealth() {
        HealthStatus status = HealthStatus.healthy("node-1", 10);
        ClusterDashboard dashboard = new ClusterDashboard(
                new MetricRegistry(), Map.of("node-1", status), 4);

        assertEquals(status, dashboard.getNodeHealth("node-1"));
        assertNull(dashboard.getNodeHealth("node-99"));
    }

    @Test
    void testGetHealthyNodeCount() {
        ClusterDashboard dashboard = new ClusterDashboard(
                new MetricRegistry(),
                Map.of(
                        "n1", HealthStatus.healthy("n1", 1),
                        "n2", HealthStatus.healthy("n2", 2),
                        "n3", HealthStatus.unhealthy("n3", "down")
                ), 4);

        assertEquals(2, dashboard.getHealthyNodeCount());
        assertEquals(3, dashboard.getNodeCount());
    }

    @Test
    void testGetNodeMetricsReturnsSnapshot() {
        MetricRegistry registry = new MetricRegistry();
        registry.record("latency", 42.0);
        ClusterDashboard dashboard = new ClusterDashboard(
                registry, Map.of(), 0);

        Map<String, Double> metrics = dashboard.getNodeMetrics("any");
        assertEquals(42.0, metrics.get("latency"));
    }
}
