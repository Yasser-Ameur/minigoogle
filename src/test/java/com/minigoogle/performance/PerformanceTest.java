package com.minigoogle.performance;

import com.minigoogle.storage.cache.PostingCache;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for performance utilities (variable-byte encoding, skip lists). */
class PerformanceTest {

    @Test
    void testVariableByteEncoder() {
        // Small value fits in 1 byte
        byte[] small = VariableByteEncoder.encode(42);
        assertEquals(1, small.length);

        // Larger value needs more bytes
        byte[] large = VariableByteEncoder.encode(200);
        assertTrue(large.length > 1);

        // Round trip
        VariableByteEncoder.DecodeResult result = VariableByteEncoder.decode(small, 0);
        assertEquals(42, result.value());
        assertEquals(1, result.bytesConsumed());
    }

    @Test
    void testVariableByteEncoderArray() {
        int[] values = {0, 1, 127, 128, 300, 100000};
        byte[] encoded = VariableByteEncoder.encode(values);
        int[] decoded = VariableByteEncoder.decodeAll(encoded);
        assertArrayEquals(values, decoded);
    }

    @Test
    void testSkipListIndex() {
        int[] sorted = {1, 3, 5, 7, 9, 11, 13, 15};
        SkipListIndex index = new SkipListIndex(sorted);

        assertEquals(0, index.seek(1));   // Found at index 0
        assertEquals(2, index.seek(5));   // Found at index 2
        assertEquals(8, index.seek(20));  // Beyond end
        assertTrue(index.contains(7));
        assertFalse(index.contains(8));
    }

    @Test
    void testCpuProfiler() {
        CpuProfiler profiler = new CpuProfiler();
        CpuProfiler.ProfileResult result = profiler.profile("test", () -> {
            // Do some work
            int sum = 0;
            for (int i = 0; i < 1000; i++) sum += i;
        });
        assertNotNull(result.label());
        assertTrue(result.wallTimeMs() >= 0);
    }

    @Test
    void testPostingCache() {
        PostingCache cache = new PostingCache(100);
        assertNull(cache.get("term1"));

        com.minigoogle.indexer.inverted.PostingList pl =
                new com.minigoogle.indexer.inverted.PostingList();
        cache.put("term1", pl);
        assertEquals(pl, cache.get("term1"));
        assertTrue(cache.containsKey("term1"));
        assertEquals(1, cache.size());
    }

    @Test
    void testPostingCacheLruEviction() {
        PostingCache cache = new PostingCache(3);
        for (int i = 0; i < 5; i++) {
            cache.put("term" + i,
                    new com.minigoogle.indexer.inverted.PostingList());
        }
        assertEquals(3, cache.size());
    }

    @Test
    void testBenchmarkReport() {
        com.minigoogle.monitoring.benchmark.BenchmarkReport report = new com.minigoogle.monitoring.benchmark.BenchmarkReport(
                "test", 100,
                java.util.List.of(1_000_000L, 2_000_000L, 3_000_000L),
                java.time.Duration.ofMillis(10));
        assertEquals(100, report.iterations());
        assertTrue(report.averageLatencyMs() > 0);
        assertTrue(report.throughputPerSecond() > 0);
        assertNotNull(report.summary());
    }
}
