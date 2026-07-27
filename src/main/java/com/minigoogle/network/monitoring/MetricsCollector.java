package com.minigoogle.network.monitoring;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects and aggregates metrics from all services in the cluster.
 *
 * Per ARCHITECTURE.md sections:
 * - Ch07 §19 (Observability): metrics exposed include average query latency,
 *   queries/second, CPU usage, memory usage, shard size, heartbeat delay.
 * - Ch11 §4 (Metrics Collection): every service exports metrics, each
 *   metric is timestamped.
 *
 * Thread-safe: all mutations go through ConcurrentHashMap / synchronized lists.
 */
public class MetricsCollector {

    private final Map<String, List<MetricEntry>> metrics = new ConcurrentHashMap<>();
    private final int maxEntriesPerMetric;

    public MetricsCollector(int maxEntriesPerMetric) {
        this.maxEntriesPerMetric = maxEntriesPerMetric;
    }

    public MetricsCollector() {
        this(1000);
    }

    /**
     * Records a metric value with the current timestamp.
     */
    public void record(String name, double value) {
        record(name, value, Instant.now());
    }

    /**
     * Records a metric value at a specific timestamp.
     */
    public void record(String name, double value, Instant timestamp) {
        MetricEntry entry = new MetricEntry(value, timestamp);
        metrics.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>())).add(entry);
        // Trim if over capacity
        List<MetricEntry> list = metrics.get(name);
        while (list.size() > maxEntriesPerMetric) {
            list.remove(0);
        }
    }

    /**
     * Returns all recorded entries for a metric name.
     */
    public List<MetricEntry> getMetrics(String name) {
        List<MetricEntry> list = metrics.get(name);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }

    /**
     * Returns the most recent value for a metric, or 0.0 if none recorded.
     */
    public double getLatest(String name) {
        List<MetricEntry> list = metrics.get(name);
        if (list == null || list.isEmpty()) {
            return 0.0;
        }
        return list.get(list.size() - 1).value();
    }

    /**
     * Returns the average value across all recorded entries for a metric.
     */
    public double getAverage(String name) {
        List<MetricEntry> list = metrics.get(name);
        if (list == null || list.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (MetricEntry e : list) {
            sum += e.value();
        }
        return sum / list.size();
    }

    /**
     * Returns a snapshot of all metrics and their latest values.
     */
    public Map<String, Double> getSnapshot() {
        Map<String, Double> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<MetricEntry>> entry : metrics.entrySet()) {
            List<MetricEntry> list = entry.getValue();
            if (!list.isEmpty()) {
                snapshot.put(entry.getKey(), list.get(list.size() - 1).value());
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Returns the set of all metric names that have been recorded.
     */
    public java.util.Set<String> metricNames() {
        return Collections.unmodifiableSet(metrics.keySet());
    }

    /**
     * Clears all recorded metrics.
     */
    public void clear() {
        metrics.clear();
    }

    /**
     * A single metric data point.
     */
    public record MetricEntry(double value, Instant timestamp) {
    }
}
