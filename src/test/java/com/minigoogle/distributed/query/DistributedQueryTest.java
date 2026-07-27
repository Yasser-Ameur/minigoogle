package com.minigoogle.distributed.query;

import com.minigoogle.distributed.query.cache.DistributedQueryCache;
import com.minigoogle.distributed.query.coordinator.DistributedSearchCoordinator;
import com.minigoogle.distributed.query.execution.DistributedExecutor;
import com.minigoogle.distributed.query.execution.LocalSearchExecutor;
import com.minigoogle.distributed.query.merge.GlobalResultMerger;
import com.minigoogle.distributed.query.merge.KWayMerger;
import com.minigoogle.distributed.query.model.LocalSearchResponse;
import com.minigoogle.distributed.query.model.QueryContext;
import com.minigoogle.network.dto.SearchResponse;
import com.minigoogle.network.dto.SearchResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for distributed query execution and merging functionality. */
class DistributedQueryTest {

    // ── KWayMerger ──

    @Test
    void testKWayMergerMergesAndTrimsToTopK() {
        KWayMerger merger = new KWayMerger();

        List<SearchResult> shard0 = List.of(
                new SearchResult("url1", "T1", "S1", 10.0, 10.0, 0.0),
                new SearchResult("url2", "T2", "S2", 8.5, 8.5, 0.0)
        );
        List<SearchResult> shard1 = List.of(
                new SearchResult("url3", "T3", "S3", 9.5, 9.5, 0.0),
                new SearchResult("url4", "T4", "S4", 7.0, 7.0, 0.0)
        );
        List<SearchResult> shard2 = List.of(
                new SearchResult("url5", "T5", "S5", 11.0, 11.0, 0.0)
        );

        List<SearchResult> merged = merger.merge(List.of(shard0, shard1, shard2), 3);

        assertEquals(3, merged.size());
        // Should be sorted descending by score: 11.0, 10.0, 9.5
        assertEquals(11.0, merged.get(0).score(), 0.001);
        assertEquals(10.0, merged.get(1).score(), 0.001);
        assertEquals(9.5, merged.get(2).score(), 0.001);
    }

    // ── GlobalResultMerger ──

    @Test
    void testGlobalResultMerger() {
        GlobalResultMerger merger = new GlobalResultMerger();

        LocalSearchResponse resp0 = new LocalSearchResponse(0,
                List.of(new SearchResult("a", "A", "", 5.0, 5.0, 0.0)), 100, 10);
        LocalSearchResponse resp1 = new LocalSearchResponse(1,
                List.of(new SearchResult("b", "B", "", 8.0, 8.0, 0.0)), 200, 12);

        List<SearchResult> merged = merger.merge(List.of(resp0, resp1), 2);
        assertEquals(2, merged.size());
        assertEquals("b", merged.get(0).url()); // Higher score first
        assertEquals(300, merger.computeTotalHits(List.of(resp0, resp1)));
    }

    // ── DistributedQueryCache ──

    @Test
    void testCacheHitAndMiss() {
        DistributedQueryCache cache = new DistributedQueryCache(100);
        assertNull(cache.get("unknown query"));

        List<SearchResult> results = List.of(new SearchResult("url", "T", "S", 1.0, 1.0, 0.0));
        cache.put("hello world", results, 60_000);

        assertNotNull(cache.get("hello world"));
        assertEquals(1, cache.get("hello world").size());
    }

    @Test
    void testCacheLruEviction() {
        DistributedQueryCache cache = new DistributedQueryCache(2);
        cache.put("q1", List.of(), 60_000);
        cache.put("q2", List.of(), 60_000);
        cache.put("q3", List.of(), 60_000); // should evict q1

        assertNull(cache.get("q1"));
        assertNotNull(cache.get("q2"));
        assertNotNull(cache.get("q3"));
    }

    // ── DistributedExecutor with LocalSearchExecutor ──

    @Test
    void testDistributedExecutorScatterGather() {
        // Create 3 fake shard executors that return canned results
        List<LocalSearchExecutor> executors = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int shardId = i;
            executors.add(new LocalSearchExecutor(shardId, (query, topK) ->
                    List.of(new SearchResult("url-" + shardId, "Title-" + shardId, query, 10.0 - shardId, 10.0 - shardId, 0.0))
            ));
        }

        DistributedExecutor distExec = new DistributedExecutor(4);
        QueryContext context = new QueryContext("test query", 10, Duration.ofSeconds(5));

        List<LocalSearchResponse> responses = distExec.scatter(executors, context, 5000);

        assertEquals(3, responses.size());
        distExec.shutdown();
    }

    // ── Full Pipeline: DistributedSearchCoordinator ──

    @Test
    void testFullDistributedSearchPipeline() {
        List<LocalSearchExecutor> executors = List.of(
                new LocalSearchExecutor(0, (q, k) -> List.of(
                        new SearchResult("url-a", "A", q, 9.0, 9.0, 0.0),
                        new SearchResult("url-b", "B", q, 7.0, 7.0, 0.0)
                )),
                new LocalSearchExecutor(1, (q, k) -> List.of(
                        new SearchResult("url-c", "C", q, 10.0, 10.0, 0.0),
                        new SearchResult("url-d", "D", q, 6.0, 6.0, 0.0)
                ))
        );

        DistributedSearchCoordinator coordinator = new DistributedSearchCoordinator(
                executors, 4, Duration.ofSeconds(5), 100
        );

        SearchResponse response = coordinator.search("compiler optimization", 3);

        assertEquals(3, response.results().size());
        // Top 3 should be: 10.0 (url-c), 9.0 (url-a), 7.0 (url-b)
        assertEquals("url-c", response.results().get(0).url());
        assertEquals("url-a", response.results().get(1).url());
        assertEquals("url-b", response.results().get(2).url());

        // Second call should hit cache
        SearchResponse cached = coordinator.search("compiler optimization", 3);
        assertTrue(cached.executionTimeMs() <= response.executionTimeMs());
        assertEquals(3, cached.results().size());

        coordinator.shutdown();
    }
}
