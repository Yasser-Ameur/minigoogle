package com.minigoogle.query.cache;

import com.minigoogle.query.result.SearchResult;
import com.minigoogle.indexer.model.IndexedDocument;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for QueryCache functionality. */
class QueryCacheTest {

    @Test
    void testPutAndGet() {
        QueryCache cache = new QueryCache(10);
        SearchResult result = new SearchResult(
                new IndexedDocument(UUID.randomUUID(), URI.create("http://a.com"), "A", 100, java.time.Instant.now()),
                9.5);
        cache.put("test query", List.of(result));

        List<SearchResult> cached = cache.get("test query");
        assertNotNull(cached);
        assertEquals(1, cached.size());
        assertEquals(9.5, cached.get(0).score());
    }

    @Test
    void testLruEviction() {
        QueryCache cache = new QueryCache(3);
        for (int i = 0; i < 5; i++) {
            cache.put("query" + i, List.of(new SearchResult(
                    new IndexedDocument(UUID.randomUUID(), URI.create("http://" + i + ".com"), "T", 100, java.time.Instant.now()),
                    i)));
        }
        // With capacity 3, queries 0 and 1 should be evicted
        assertNull(cache.get("query0"));
        assertNull(cache.get("query1"));
        assertNotNull(cache.get("query2"));
        assertNotNull(cache.get("query3"));
        assertNotNull(cache.get("query4"));
        assertEquals(3, cache.size());
    }

    @Test
    void testQueryNormalization() {
        QueryCache cache = new QueryCache(10);
        cache.put("Test Query", List.of(new SearchResult(
                new IndexedDocument(UUID.randomUUID(), URI.create("http://a.com"), "A", 100, java.time.Instant.now()),
                5.0)));
        // Same query with different case should hit cache
        assertNotNull(cache.get("test query"));
        assertNotNull(cache.get("TEST QUERY"));
    }

    @Test
    void testMultiWordQueriesNormalizeWhitespace() {
        QueryCache cache = new QueryCache(10);
        SearchResult result = new SearchResult(
                new IndexedDocument(UUID.randomUUID(), URI.create("http://a.com"), "A", 100, java.time.Instant.now()),
                7.0);
        cache.put("  java   python  ", List.of(result));

        // Same multi-word query with different internal spacing must hit.
        assertNotNull(cache.get("java python"));
        assertNotNull(cache.get("java    python"));
        assertEquals(1, cache.size(), "Whitespace variants must share one cache entry");
    }

    @Test
    void testDistinctMultiWordQueriesDoNotCollide() {
        QueryCache cache = new QueryCache(10);
        cache.put("new york", List.of(new SearchResult(
                new IndexedDocument(UUID.randomUUID(), URI.create("http://ny.com"), "NY", 100, java.time.Instant.now()),
                1.0)));
        cache.put("newark", List.of(new SearchResult(
                new IndexedDocument(UUID.randomUUID(), URI.create("http://nk.com"), "NK", 100, java.time.Instant.now()),
                2.0)));

        assertEquals(1.0, cache.get("new york").get(0).score());
        assertEquals(2.0, cache.get("newark").get(0).score());
        assertEquals(2, cache.size());
    }

    @Test
    void testBooleanOperatorDoesNotCollideWithImplicitAnd() {
        QueryCache cache = new QueryCache(10);
        SearchResult boolResult = new SearchResult(
                new IndexedDocument(UUID.randomUUID(), URI.create("http://bool.com"), "B", 100, java.time.Instant.now()),
                9.0);
        SearchResult implicitResult = new SearchResult(
                new IndexedDocument(UUID.randomUUID(), URI.create("http://implicit.com"), "I", 100, java.time.Instant.now()),
                8.0);

        cache.put("cat AND dog", List.of(boolResult));
        cache.put("cat and dog", List.of(implicitResult));

        // The boolean-operator query and the implicit-AND query parse differently
        // and must never share a cache key.
        assertEquals(2, cache.size(), "Boolean AND and implicit AND must be distinct entries");
        assertEquals(9.0, cache.get("cat AND dog").get(0).score());
        assertEquals(8.0, cache.get("cat and dog").get(0).score());
    }

    @Test
    void testClear() {
        QueryCache cache = new QueryCache(10);
        cache.put("a", List.of());
        cache.put("b", List.of());
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void testContainsKey() {
        QueryCache cache = new QueryCache(10);
        assertFalse(cache.containsKey("missing"));
        cache.put("present", List.of());
        assertTrue(cache.containsKey("present"));
    }

    @Test
    void testGetMissReturnsNull() {
        QueryCache cache = new QueryCache(10);
        assertNull(cache.get("nonexistent"));
    }
}
