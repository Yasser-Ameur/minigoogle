package com.minigoogle.performance;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Lightweight CPU profiler for measuring method execution time.
 *
 * Per ARCHITECTURE.md Ch12:
 *   Profiling identifies bottlenecks in query execution,
 *   index construction, and network communication.
 */
public class CpuProfiler {

    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    /**
     * Times the execution of a task and returns elapsed CPU time in nanoseconds.
     *
     * @param task The task to profile.
     * @return CPU time consumed in nanoseconds.
     */
    public long profileCpuTime(Runnable task) {
        long startCpu = threadMXBean.getCurrentThreadCpuTime();
        task.run();
        return threadMXBean.getCurrentThreadCpuTime() - startCpu;
    }

    /**
     * Times the execution of a task and returns elapsed wall-clock time in nanoseconds.
     */
    public long profileWallTime(Runnable task) {
        long start = System.nanoTime();
        task.run();
        return System.nanoTime() - start;
    }

    /**
     * Profiles a task and returns a ProfileResult with both CPU and wall time.
     */
    public ProfileResult profile(String label, Runnable task) {
        long startCpu = threadMXBean.getCurrentThreadCpuTime();
        long startWall = System.nanoTime();
        task.run();
        long cpuTime = threadMXBean.getCurrentThreadCpuTime() - startCpu;
        long wallTime = System.nanoTime() - startWall;
        return new ProfileResult(label, cpuTime, wallTime);
    }

    /**
     * Returns the current thread's CPU time in nanoseconds.
     */
    public long getCurrentCpuTime() {
        return threadMXBean.getCurrentThreadCpuTime();
    }

    /**
     * Returns the current thread's user time in nanoseconds.
     */
    public long getCurrentUserTime() {
        return threadMXBean.getCurrentThreadUserTime();
    }

    public record ProfileResult(String label, long cpuTimeNanos, long wallTimeNanos) {
        public double cpuTimeMs() { return cpuTimeNanos / 1_000_000.0; }
        public double wallTimeMs() { return wallTimeNanos / 1_000_000.0; }
        public double cpuUtilization() {
            return wallTimeNanos == 0 ? 0.0 : (double) cpuTimeNanos / wallTimeNanos;
        }

        @Override
        public String toString() {
            return String.format("%s: cpu=%.2fms, wall=%.2fms, util=%.1f%%",
                    label, cpuTimeMs(), wallTimeMs(), cpuUtilization() * 100);
        }
    }
}
