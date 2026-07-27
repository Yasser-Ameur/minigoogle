package com.minigoogle.monitoring;

import com.minigoogle.monitoring.alerts.AlertManager;
import com.minigoogle.monitoring.analytics.QueryAnalytics;
import com.minigoogle.monitoring.benchmark.BenchmarkReport;
import com.minigoogle.monitoring.benchmark.BenchmarkRunner;
import com.minigoogle.monitoring.health.HealthStatus;
import com.minigoogle.monitoring.logging.LogFormatter;
import com.minigoogle.monitoring.metrics.MetricRegistry;
import com.minigoogle.monitoring.tracing.Span;
import com.minigoogle.monitoring.tracing.TraceManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for monitoring subsystem (metrics, alerts, tracing, analytics). */
class MonitoringTest {

    @Test
    void testMetricRegistry() {
        MetricRegistry registry = new MetricRegistry();
        registry.record("latency", 15.0);
        registry.record("latency", 25.0);
        assertEquals(25.0, registry.getLatest("latency"));
        assertEquals(20.0, registry.getAverage("latency"), 0.001);
    }

    @Test
    void testTraceManager() {
        TraceManager traceManager = new TraceManager();
        String traceId = traceManager.startTrace("search");
        assertNotNull(traceId);

        Span span = traceManager.createSpan(traceId, "query");
        assertNotNull(span);
        span.finish();

        List<Span> trace = traceManager.getTrace(traceId);
        assertEquals(2, trace.size());
    }

    @Test
    void testHealthStatus() {
        HealthStatus healthy = HealthStatus.healthy("node-1", 10);
        assertTrue(healthy.isHealthy());
        assertEquals(HealthStatus.Status.HEALTHY, healthy.status());

        HealthStatus unhealthy = HealthStatus.unhealthy("node-2", "down");
        assertFalse(unhealthy.isHealthy());
    }

    @Test
    void testBenchmarkRunner() {
        BenchmarkRunner runner = new BenchmarkRunner("test", 100);
        BenchmarkReport report = runner.run(() -> {
            // do nothing
        });
        assertEquals(100, report.iterations());
        assertTrue(report.averageLatencyMs() >= 0);
        assertTrue(report.throughputPerSecond() >= 0);
    }

    @Test
    void testAlertManager() {
        AlertManager manager = new AlertManager();
        manager.addRule("cpu", new AlertManager.AlertRule("cpu", 80.0, "WARN"));
        manager.evaluate(Map.of("cpu", 90.0));
        assertEquals(1, manager.getFiredAlerts().size());
    }

    @Test
    void testQueryAnalytics() {
        QueryAnalytics analytics = new QueryAnalytics();
        analytics.recordQuery("test", 5, 10);
        analytics.recordQuery("test", 0, 20);
        assertEquals(2, analytics.getTotalQueries());
        assertEquals(0.5, analytics.getZeroResultRate(), 0.001);
    }

    @Test
    void testLogFormatter() {
        String formatted = LogFormatter.format("INFO", "node-1", "abc-123", "search completed");
        assertTrue(formatted.contains("INFO"));
        assertTrue(formatted.contains("node-1"));
    }
}
