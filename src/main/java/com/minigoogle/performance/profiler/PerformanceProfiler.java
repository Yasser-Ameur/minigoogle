package com.minigoogle.performance.profiler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Profiles code execution by tracking timing and call counts for named sections.
 *
 * <p>Sections are started with {@link #startSection(String)} and ended with
 * {@link #endSection(String)}. Each call to {@code endSection} records the
 * elapsed time and increments the call count for that section. Statistics
 * are retrieved via {@link #getStats()}.</p>
 *
 * <p>This class is thread-safe: all internal state is protected by
 * {@link ConcurrentHashMap}.</p>
 */
public class PerformanceProfiler {

    private final ConcurrentHashMap<String, long[]> sections = new ConcurrentHashMap<>();

    /**
     * Starts timing a named section. If the section does not yet exist,
     * it is created automatically.
     *
     * @param name The name of the section to start.
     */
    public void startSection(String name) {
        sections.computeIfAbsent(name, k -> new long[]{0L, 0})[0] = -System.nanoTime();
    }

    /**
     * Ends timing for the named section and records the elapsed duration.
     *
     * @param name The name of the section to end.
     * @throws IllegalStateException If the section was never started.
     */
    public void endSection(String name) {
        long[] stats = sections.get(name);
        if (stats == null || stats[0] == 0) {
            throw new IllegalStateException("Section '" + name + "' was never started");
        }
        long elapsed = System.nanoTime() + stats[0];
        stats[0] += elapsed;
        stats[1]++;
    }

    /**
     * Returns a snapshot of all section statistics.
     *
     * @return An unmodifiable map of section names to their {@link SectionStats}.
     */
    public Map<String, SectionStats> getStats() {
        var result = new java.util.HashMap<String, SectionStats>();
        for (var entry : sections.entrySet()) {
            String name = entry.getKey();
            long[] raw = entry.getValue();
            long totalNanos = raw[0];
            int callCount = (int) raw[1];
            double avgNanos = callCount > 0 ? (double) totalNanos / callCount : 0.0;
            result.put(name, new SectionStats(totalNanos, callCount, avgNanos));
        }
        return Map.copyOf(result);
    }

    /**
     * Clears all recorded profiling data.
     */
    public void clear() {
        sections.clear();
    }

    /**
     * Statistics for a single profiled section.
     *
     * @param totalNanos The total elapsed time in nanoseconds across all calls.
     * @param callCount  The number of times this section was completed.
     * @param avgNanos   The average elapsed time per call in nanoseconds.
     */
    public record SectionStats(long totalNanos, int callCount, double avgNanos) {
    }
}
