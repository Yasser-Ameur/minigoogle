package com.minigoogle.performance.benchmark;

/**
 * Runs micro-benchmarks with configurable warmup and measurement iterations.
 *
 * <p>Warmup iterations allow the JIT compiler to optimize the benchmarked code
 * before timing begins. Measurement iterations collect timing data using
 * {@link System#nanoTime()} and compute average and standard deviation.</p>
 */
public class MicroBenchmark {

    private final Runnable task;

    /**
     * Constructs a micro-benchmark wrapping the given task.
     *
     * @param task The benchmark task to execute.
     */
    public MicroBenchmark(Runnable task) {
        this.task = task;
    }

    /**
     * Runs the benchmark with the specified number of warmup and measurement iterations.
     *
     * @param warmupIterations     The number of iterations to run before timing.
     * @param measurementIterations The number of iterations to time.
     * @return The collected benchmark results.
     */
    public BenchmarkResult run(int warmupIterations, int measurementIterations) {
        for (int i = 0; i < warmupIterations; i++) {
            task.run();
        }

        long[] timings = new long[measurementIterations];
        for (int i = 0; i < measurementIterations; i++) {
            long start = System.nanoTime();
            task.run();
            timings[i] = System.nanoTime() - start;
        }

        double sum = 0.0;
        for (long t : timings) {
            sum += t;
        }
        double avg = sum / measurementIterations;

        double varianceSum = 0.0;
        for (long t : timings) {
            double diff = t - avg;
            varianceSum += diff * diff;
        }
        double stdDev = Math.sqrt(varianceSum / measurementIterations);

        return new BenchmarkResult(avg, stdDev, measurementIterations);
    }

    /**
     * Result of a single benchmark run.
     *
     * @param avgNanos   The average execution time per iteration in nanoseconds.
     * @param stdDev     The standard deviation of execution times in nanoseconds.
     * @param iterations The number of measurement iterations performed.
     */
    public record BenchmarkResult(double avgNanos, double stdDev, int iterations) {

        /**
         * @return The average execution time in milliseconds.
         */
        public double avgMillis() {
            return avgNanos / 1_000_000.0;
        }

        /**
         * @return The standard deviation in milliseconds.
         */
        public double stdDevMillis() {
            return stdDev / 1_000_000.0;
        }
    }
}
