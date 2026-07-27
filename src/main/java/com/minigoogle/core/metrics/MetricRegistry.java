package com.minigoogle.core.metrics;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MetricRegistry {

    private final Map<String, List<Metric>> store = new ConcurrentHashMap<>();
    private final int maxEntriesPerMetric;

    public MetricRegistry(int maxEntriesPerMetric) {
        this.maxEntriesPerMetric = maxEntriesPerMetric > 0 ? maxEntriesPerMetric : 1000;
    }

    public MetricRegistry() {
        this(1000);
    }

    public void record(String name, double value) {
        record(name, value, Instant.now());
    }

    public void record(String name, double value, Instant timestamp) {
        Metric metric = new Metric(name, value, timestamp);
        store.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>())).add(metric);
        List<Metric> list = store.get(name);
        while (list.size() > maxEntriesPerMetric) {
            list.remove(0);
        }
    }

    public List<Metric> getMetrics(String name) {
        List<Metric> list = store.get(name);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }

    public double getLatest(String name) {
        List<Metric> list = store.get(name);
        if (list == null || list.isEmpty()) return 0.0;
        return list.get(list.size() - 1).value();
    }

    public double getAverage(String name) {
        List<Metric> list = store.get(name);
        if (list == null || list.isEmpty()) return 0.0;
        double sum = 0;
        for (Metric m : list) sum += m.value();
        return sum / list.size();
    }

    public Map<String, Double> getSnapshot() {
        Map<String, Double> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<Metric>> entry : store.entrySet()) {
            List<Metric> list = entry.getValue();
            if (!list.isEmpty()) {
                snapshot.put(entry.getKey(), list.get(list.size() - 1).value());
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public Set<String> metricNames() {
        return Collections.unmodifiableSet(store.keySet());
    }

    public void clear() {
        store.clear();
    }
}
