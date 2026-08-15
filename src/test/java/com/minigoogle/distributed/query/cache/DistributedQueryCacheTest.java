package com.minigoogle.distributed.query.cache;

import com.minigoogle.network.dto.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the distributed (coordinator) query cache. Regression coverage for
 * the #8 cache-key collision: boolean operators (uppercase-only in the Lexer)
 * must never share a cache key with the lowercase word, so {@code cat AND dog}
 * (boolean AND) and {@code cat and dog} (implicit AND) are served as two
 * distinct entries.
 */
class DistributedQueryCacheTest {

    private static SearchResult result(String url) {
        return new SearchResult(url, "Title", "Snippet", 1.0, 1.0, 1.0);
    }

    @Test
    void operatorQueryDoesNotCollideWithImplicitAndQuery() {
        DistributedQueryCache cache = new DistributedQueryCache(10);

        cache.put("cat AND dog", List.of(result("http://bool")));
        cache.put("cat and dog", List.of(result("http://implicit")));

        assertEquals(2, cache.size(), "Boolean-AND and implicit-AND must be distinct entries");
        assertNotNull(cache.get("cat AND dog"), "Boolean-AND query must hit its own entry");
        assertNotNull(cache.get("cat and dog"), "Implicit-AND query must hit its own entry");
        assertEquals("http://bool", cache.get("cat AND dog").get(0).url());
        assertEquals("http://implicit", cache.get("cat and dog").get(0).url());
    }

    @Test
    void whitespaceAndCaseVariantsStillShareOneEntry() {
        DistributedQueryCache cache = new DistributedQueryCache(10);
        cache.put("  cat   AND  dog  ", List.of(result("http://bool")));

        assertNotNull(cache.get("cat AND dog"));
        assertNotNull(cache.get("CAT AND DOG"));
        assertNotNull(cache.get("cat     AND     dog"));
        assertEquals(1, cache.size());
    }

    @Test
    void notOperatorPreservesIdentity() {
        DistributedQueryCache cache = new DistributedQueryCache(10);
        cache.put("cat NOT dog", List.of(result("http://bool")));
        cache.put("cat not dog", List.of(result("http://implicit")));

        assertEquals(2, cache.size());
        assertNotNull(cache.get("cat NOT dog"));
        assertNotNull(cache.get("cat not dog"));
    }

    @Test
    void plainQueriesCollideIgnoringCaseAndWhitespace() {
        DistributedQueryCache cache = new DistributedQueryCache(10);
        cache.put("Distributed Systems", List.of(result("http://ds")));

        assertNotNull(cache.get("distributed systems"));
        assertNotNull(cache.get("  distributed    systems  "));
        assertEquals(1, cache.size());
    }

    @Test
    void getReturnsNullOnMiss() {
        DistributedQueryCache cache = new DistributedQueryCache(10);
        assertNull(cache.get("missing"));
    }

    @Test
    void clearEmptiesCache() {
        DistributedQueryCache cache = new DistributedQueryCache(10);
        cache.put("java", List.of(result("http://j")));
        cache.put("python", List.of(result("http://p")));
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("java"));
    }
}
