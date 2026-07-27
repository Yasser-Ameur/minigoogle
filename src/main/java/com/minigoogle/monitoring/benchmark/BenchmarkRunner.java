package com.minigoogle.monitoring.benchmark;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs benchmarks and produces performance reports.
 *
 * Per ARCHITECTURE.md Ch11:
 *   Benchmarks validate algorithm correctness and measure performance.
 *   Report includes throughput, latency percentiles, and resource usage.
 */
public class BenchmarkRunner {

    private final String name;
    private final int iterations;

    public BenchmarkRunner(String name, int iterations) {
        this.name = name;
        this.iterations = iterations;
    }

    public BenchmarkRunner(String name) {
        this(name, 1000);
    }

    /**
     * Runs a benchmark task and returns a report with timing data.
     *
     * @param task The task to benchmark.
     * @return A report with latency statistics.
     */
    public BenchmarkReport run(Runnable task) {
        List<Long> latencies = new ArrayList<>(iterations);
        Instant wallStart = Instant.now();

        for (int i = 0; i < iterations; i++) {
            Instant start = Instant.now();
            task.run();
            latencies.add(Duration.between(start, Instant.now()).toNanos());
        }

        Duration wallTime = Duration.between(wallStart, Instant.now());
        return new BenchmarkReport(name, iterations, latencies, wallTime);
    }

    /**
     * Runs a benchmark task that returns a result, and measures timing.
     */
    public <T> BenchmarkReport run(java.util.function.Supplier<T> task) {
        List<Long> latencies = new ArrayList<>(iterations);
        Instant wallStart = Instant.now();

        for (int i = 0; i < iterations; i++) {
            Instant start = Instant.now();
            task.get();
            latencies.add(Duration.between(start, Instant.now()).toNanos());
        }

        Duration wallTime = Duration.between(wallStart, Instant.now());
        return new BenchmarkReport(name, iterations, latencies, wallTime);
    }
}
