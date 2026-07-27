package com.minigoogle.monitoring.analytics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects and analyzes query patterns for insights.
 *
 * Per ARCHITECTURE.md Ch11:
 *   Query analytics track popular queries, zero-result rates,
 *   average result counts, and query latency distributions.
 */
public class QueryAnalytics {

    private final Map<String, AtomicInteger> queryCounts = new ConcurrentHashMap<>();
    private final LongAdder totalQueries = new LongAdder();
    private final LongAdder zeroResultQueries = new LongAdder();
    private final LongAdder totalLatencyMs = new LongAdder();

    /**
     * Records a completed query.
     *
     * @param query        The query string.
     * @param resultCount  The number of results returned.
     * @param latencyMs    The query latency in milliseconds.
     */
    public void recordQuery(String query, int resultCount, long latencyMs) {
        totalQueries.increment();
        totalLatencyMs.add(latencyMs);
        if (resultCount == 0) {
            zeroResultQueries.increment();
        }
        queryCounts.computeIfAbsent(query.toLowerCase().strip(), k -> new AtomicInteger())
                .incrementAndGet();
    }

    /**
     * Returns the total number of queries recorded.
     */
    public long getTotalQueries() {
        return totalQueries.sum();
    }

    /**
     * Returns the average query latency in milliseconds.
     */
    public double getAverageLatencyMs() {
        long total = totalQueries.sum();
        return total == 0 ? 0.0 : (double) totalLatencyMs.sum() / total;
    }

    /**
     * Returns the zero-result query rate as a fraction [0.0, 1.0].
     */
    public double getZeroResultRate() {
        long total = totalQueries.sum();
        return total == 0 ? 0.0 : (double) zeroResultQueries.sum() / total;
    }

    /**
     * Returns the count for a specific query.
     */
    public int getQueryCount(String query) {
        AtomicInteger count = queryCounts.get(query.toLowerCase().strip());
        return count != null ? count.get() : 0;
    }

    /**
     * Returns the top N most popular queries.
     */
    public java.util.List<Map.Entry<String, Integer>> getTopQueries(int n) {
        java.util.List<Map.Entry<String, Integer>> sorted = new java.util.ArrayList<>();
        for (var entry : queryCounts.entrySet()) {
            sorted.add(Map.entry(entry.getKey(), entry.getValue().get()));
        }
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return sorted.subList(0, Math.min(n, sorted.size()));
    }

    /**
     * Returns the total number of unique queries.
     */
    public int uniqueQueryCount() {
        return queryCounts.size();
    }

    /**
     * Clears all analytics data.
     */
    public void clear() {
        queryCounts.clear();
        totalQueries.reset();
        zeroResultQueries.reset();
        totalLatencyMs.reset();
    }
}
