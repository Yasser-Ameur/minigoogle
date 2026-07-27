package com.minigoogle.performance.benchmark;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Benchmarks distributed operations across multiple simulated nodes.
 *
 * <p>Registers named benchmarks and executes them in parallel across virtual
 * threads to simulate concurrent node operations. Results are collected
 * using a {@link MicroBenchmark} per benchmark function.</p>
 */
public class ClusterBenchmark {

    private final int nodeCount;
    private final Map<String, Runnable> benchmarks = new LinkedHashMap<>();

    /**
     * Constructs a cluster benchmark targeting the given number of simulated nodes.
     *
     * @param nodeCount The number of virtual nodes to simulate during execution.
     */
    public ClusterBenchmark(int nodeCount) {
        this.nodeCount = nodeCount;
    }

    /**
     * Registers a named benchmark to be executed across all nodes.
     *
     * @param name      The name of the benchmark.
     * @param benchmark The task to execute on each node.
     */
    public void addBenchmark(String name, Runnable benchmark) {
        benchmarks.put(name, benchmark);
    }

    /**
     * Runs all registered benchmarks across simulated nodes using virtual threads.
     *
     * @param iterations The number of measurement iterations per benchmark.
     * @return A map of benchmark names to their results.
     */
    public Map<String, MicroBenchmark.BenchmarkResult> runAll(int iterations) {
        Map<String, MicroBenchmark.BenchmarkResult> results = new ConcurrentHashMap<>();

        for (var entry : benchmarks.entrySet()) {
            String name = entry.getKey();
            Runnable benchmark = entry.getValue();

            MicroBenchmark bench = new MicroBenchmark(() -> {
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                CountDownLatch latch = new CountDownLatch(nodeCount);

                for (int n = 0; n < nodeCount; n++) {
                    executor.submit(() -> {
                        try {
                            benchmark.run();
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                executor.close();
            });

            results.put(name, bench.run(Math.max(iterations / 5, 1), iterations));
        }

        return results;
    }

    /**
     * @return The number of simulated nodes for this cluster benchmark.
     */
    public int nodeCount() {
        return nodeCount;
    }
}
