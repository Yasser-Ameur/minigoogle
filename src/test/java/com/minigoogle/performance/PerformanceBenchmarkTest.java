package com.minigoogle.performance;

import com.minigoogle.monitoring.benchmark.BenchmarkReport;
import com.minigoogle.monitoring.benchmark.BenchmarkRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for performance benchmarking functionality. */
class PerformanceBenchmarkTest {

    @Test
    void testBenchmarkSearch() {
        PerformanceBenchmark benchmark = new PerformanceBenchmark("test", 10);
        BenchmarkReport report = benchmark.benchmarkSearch(() -> "result");
        assertEquals(10, report.iterations());
        assertTrue(report.averageLatencyMs() >= 0);
    }

    @Test
    void testBenchmarkWithProfiling() {
        PerformanceBenchmark benchmark = new PerformanceBenchmark("profile-test", 5);
        BenchmarkReport report = benchmark.benchmarkWithProfiling("task", () -> {
            int sum = 0;
            for (int i = 0; i < 100; i++) sum += i;
        });
        assertEquals(5, report.iterations());
    }

    @Test
    void testLoadTest() {
        PerformanceBenchmark benchmark = new PerformanceBenchmark("load-test", 10);
        String summary = benchmark.loadTest(() -> "ok", 2, 20);
        assertTrue(summary.contains("Load Test"));
        assertTrue(summary.contains("20 queries"));
        assertTrue(summary.contains("success=20"));
    }

    @Test
    void testLoadTestWithFailures() {
        PerformanceBenchmark benchmark = new PerformanceBenchmark("fail-test", 10);
        String summary = benchmark.loadTest(() -> {
            if (Math.random() < 0.5) throw new RuntimeException("fail");
            return "ok";
        }, 2, 10);
        assertTrue(summary.contains("Load Test"));
    }
}
