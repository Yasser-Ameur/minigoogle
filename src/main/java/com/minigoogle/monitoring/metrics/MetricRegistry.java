package com.minigoogle.monitoring.metrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central metric registry for the entire cluster.
 *
 * Per ARCHITECTURE.md Ch11 §4:
 *   Every service exports metrics.
 *   Crawler → Pages Crawled/Second
 *   Indexer → Documents Indexed/Second
 *   Search Node → Queries/Second
 *   Coordinator → Average Query Latency
 *   Node → CPU, Memory, Disk, Network
 *   Metrics are updated continuously and each metric is timestamped.
 *
 * Data model per spec:
 *   public record Metric(String name, double value, Instant timestamp) {}
 */
public class MetricRegistry {

    private final Map<String, List<Metric>> store = new ConcurrentHashMap<>();

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
        store.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new Metric(name, value, timestamp));
    }

    /**
     * Returns all recorded values for a metric.
     */
    public List<Metric> getMetrics(String name) {
        List<Metric> list = store.get(name);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }

    /**
     * Returns the latest value for a metric, or 0.0 if none recorded.
     */
    public double getLatest(String name) {
        List<Metric> list = store.get(name);
        if (list == null || list.isEmpty()) return 0.0;
        return list.get(list.size() - 1).value();
    }

    /**
     * Returns the average across all recorded values for a metric.
     */
    public double getAverage(String name) {
        List<Metric> list = store.get(name);
        if (list == null || list.isEmpty()) return 0.0;
        double sum = 0;
        for (Metric m : list) sum += m.value();
        return sum / list.size();
    }

    /**
     * Returns a snapshot of all metrics: name → latest value.
     */
    public Map<String, Double> getSnapshot() {
        var snapshot = new java.util.LinkedHashMap<String, Double>();
        for (var entry : store.entrySet()) {
            List<Metric> list = entry.getValue();
            if (!list.isEmpty()) {
                snapshot.put(entry.getKey(), list.get(list.size() - 1).value());
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Returns all metric names that have been recorded.
     */
    public java.util.Set<String> metricNames() {
        return Collections.unmodifiableSet(store.keySet());
    }

    /**
     * Clears all recorded metrics.
     */
    public void clear() {
        store.clear();
    }

    /**
     * A single metric data point.
     */
    public record Metric(String name, double value, Instant timestamp) {
    }
}
