package com.minigoogle.performance;

import com.minigoogle.monitoring.benchmark.BenchmarkRunner;
import com.minigoogle.monitoring.benchmark.BenchmarkReport;

/**
 * End-to-end performance benchmark for the search engine.
 *
 * Per ARCHITECTURE.md Ch15 §16:
 *   Load testing: 1000 concurrent users, 1 million queries.
 *   Measure: Latency, Throughput, Failures, CPU, Memory.
 *   Target: P95 < 50 ms.
 */
public class PerformanceBenchmark {

    private final BenchmarkRunner benchmarkRunner;
    private final CpuProfiler cpuProfiler;

    public PerformanceBenchmark(String name, int iterations) {
        this.benchmarkRunner = new BenchmarkRunner(name, iterations);
        this.cpuProfiler = new CpuProfiler();
    }

    public PerformanceBenchmark() {
        this("MiniGoogle-Benchmark", 1000);
    }

    /**
     * Benchmarks a search query operation.
     *
     * @param queryFunction The function that executes the query.
     * @return A report with latency and throughput statistics.
     */
    public BenchmarkReport benchmarkSearch(java.util.function.Supplier<?> queryFunction) {
        return benchmarkRunner.run(queryFunction);
    }

    /**
     * Benchmarks with CPU profiling.
     */
    public BenchmarkReport benchmarkWithProfiling(String label, Runnable task) {
        CpuProfiler.ProfileResult profileResult = cpuProfiler.profile(label, task);
        System.out.println(profileResult);
        return benchmarkRunner.run(task);
    }

    /**
     * Runs a load test simulating concurrent users.
     *
     * @param queryFunction The query function to test.
     * @param concurrency   Number of concurrent threads.
     * @param totalQueries  Total number of queries to execute.
     * @return Summary results.
     */
    public String loadTest(java.util.function.Supplier<?> queryFunction,
                           int concurrency, int totalQueries) {
        java.util.concurrent.atomic.LongAdder successCount = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder failCount = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder totalLatency = new java.util.concurrent.atomic.LongAdder();

        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(concurrency);

        long startWall = System.nanoTime();
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(totalQueries);

        for (int i = 0; i < totalQueries; i++) {
            executor.submit(() -> {
                try {
                    long start = System.nanoTime();
                    queryFunction.get();
                    totalLatency.add(System.nanoTime() - start);
                    successCount.increment();
                } catch (Exception e) {
                    failCount.increment();
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdown();

        long wallTimeNanos = System.nanoTime() - startWall;
        double wallTimeSec = wallTimeNanos / 1_000_000_000.0;
        long successes = successCount.sum();
        double avgLatencyMs = successes == 0 ? 0 : (totalLatency.sum() / (double) successes) / 1_000_000.0;

        return String.format(
                "Load Test: %d queries, %d concurrency, %.1fs wall time, " +
                        "avg=%.2fms, throughput=%.0f qps, success=%d, fail=%d",
                totalQueries, concurrency, wallTimeSec, avgLatencyMs,
                totalQueries / wallTimeSec, successes, failCount.sum());
    }
}
