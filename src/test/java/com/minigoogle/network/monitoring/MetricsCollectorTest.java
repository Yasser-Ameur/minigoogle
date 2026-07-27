package com.minigoogle.network.monitoring;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for MetricsCollector functionality. */
class MetricsCollectorTest {

    @Test
    void testRecordAndGetLatest() {
        MetricsCollector collector = new MetricsCollector();
        collector.record("latency", 15.5);
        collector.record("latency", 20.3);
        assertEquals(20.3, collector.getLatest("latency"), 0.001);
    }

    @Test
    void testGetAverage() {
        MetricsCollector collector = new MetricsCollector();
        collector.record("qps", 100);
        collector.record("qps", 200);
        collector.record("qps", 300);
        assertEquals(200.0, collector.getAverage("qps"), 0.001);
    }

    @Test
    void testGetMetrics() {
        MetricsCollector collector = new MetricsCollector();
        collector.record("cpu", 50.0, Instant.parse("2026-01-01T00:00:00Z"));
        collector.record("cpu", 60.0, Instant.parse("2026-01-01T00:01:00Z"));
        assertEquals(2, collector.getMetrics("cpu").size());
    }

    @Test
    void testGetSnapshot() {
        MetricsCollector collector = new MetricsCollector();
        collector.record("a", 1.0);
        collector.record("b", 2.0);
        var snapshot = collector.getSnapshot();
        assertEquals(2, snapshot.size());
        assertEquals(1.0, snapshot.get("a"));
        assertEquals(2.0, snapshot.get("b"));
    }

    @Test
    void testMaxEntriesPerMetric() {
        MetricsCollector collector = new MetricsCollector(3);
        for (int i = 0; i < 10; i++) {
            collector.record("x", i);
        }
        assertEquals(3, collector.getMetrics("x").size());
    }

    @Test
    void testClear() {
        MetricsCollector collector = new MetricsCollector();
        collector.record("a", 1.0);
        collector.clear();
        assertEquals(0.0, collector.getLatest("a"));
    }
}
