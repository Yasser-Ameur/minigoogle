package com.minigoogle.monitoring.benchmark;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Performance benchmark report with latency statistics.
 *
 * Per ARCHITECTURE.md Ch11:
 *   Report includes throughput, latency percentiles, and resource usage.
 */
public record BenchmarkReport(
        String name,
        int iterations,
        List<Long> latenciesNanos,
        Duration wallTime
) implements Comparable<BenchmarkReport> {

    /**
     * @return The average latency in milliseconds.
     */
    public double averageLatencyMs() {
        if (latenciesNanos.isEmpty()) return 0.0;
        long sum = 0;
        for (long l : latenciesNanos) sum += l;
        return (sum / (double) latenciesNanos.size()) / 1_000_000.0;
    }

    /**
     * @return The median (p50) latency in milliseconds.
     */
    public double p50LatencyMs() {
        return percentile(50);
    }

    /**
     * @return The p95 latency in milliseconds.
     */
    public double p95LatencyMs() {
        return percentile(95);
    }

    /**
     * @return The p99 latency in milliseconds.
     */
    public double p99LatencyMs() {
        return percentile(99);
    }

    /**
     * @return The minimum latency in milliseconds.
     */
    public double minLatencyMs() {
        if (latenciesNanos.isEmpty()) return 0.0;
        return Collections.min(latenciesNanos) / 1_000_000.0;
    }

    /**
     * @return The maximum latency in milliseconds.
     */
    public double maxLatencyMs() {
        if (latenciesNanos.isEmpty()) return 0.0;
        return Collections.max(latenciesNanos) / 1_000_000.0;
    }

    /**
     * @return Throughput in operations per second.
     */
    public double throughputPerSecond() {
        double seconds = wallTime.toMillis() / 1000.0;
        if (seconds <= 0) return 0;
        return iterations / seconds;
    }

    /**
     * @return A human-readable summary of the benchmark.
     */
    public String summary() {
        return String.format(
                "%s: %d iterations, avg=%.2fms, p50=%.2fms, p95=%.2fms, p99=%.2fms, throughput=%.0f ops/s",
                name, iterations, averageLatencyMs(), p50LatencyMs(),
                p95LatencyMs(), p99LatencyMs(), throughputPerSecond());
    }

    private double percentile(int p) {
        if (latenciesNanos.isEmpty()) return 0.0;
        List<Long> sorted = new java.util.ArrayList<>(latenciesNanos);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx) / 1_000_000.0;
    }

    @Override
    public int compareTo(BenchmarkReport other) {
        return Double.compare(this.averageLatencyMs(), other.averageLatencyMs());
    }
}
