package com.minigoogle.query.cache;

import com.minigoogle.query.result.SearchResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LRU cache for query results.
 *
 * Popular queries (e.g. "weather", "youtube") repeat frequently.
 * Storing the top-K results avoids redundant index lookups.
 *
 * Implementation: LinkedHashMap configured with accessOrder=true
 * giving true LRU semantics. Capacity is 1000 entries;
 * the eldest entry is removed automatically on overflow.
 */
public class QueryCache {

    private static final int DEFAULT_CAPACITY = 1000;

    private final int capacity;
    private final Map<String, List<SearchResult>> cache;

    public QueryCache(int capacity) {
        this.capacity = capacity;
        this.cache = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<SearchResult>> eldest) {
                return size() > QueryCache.this.capacity;
            }
        };
    }

    public QueryCache() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Retrieves cached results for a query.
     *
     * @param query The search query string.
     * @return The cached results, or null if not found.
     */
    public synchronized List<SearchResult> get(String query) {
        return cache.get(normalizeQuery(query));
    }

    /**
     * Stores results for a query.
     *
     * @param query   The search query string.
     * @param results The ranked results to cache.
     */
    public synchronized void put(String query, List<SearchResult> results) {
        cache.put(normalizeQuery(query), new ArrayList<>(results));
    }

    /**
     * @return The current number of cached entries.
     */
    public synchronized int size() {
        return cache.size();
    }

    /**
     * Clears all cached entries.
     */
    public synchronized void clear() {
        cache.clear();
    }

    /**
     * Returns true if the cache contains results for the given query.
     */
    public synchronized boolean containsKey(String query) {
        return cache.containsKey(normalizeQuery(query));
    }

    /**
     * Normalizes the cache key from the query's lexical token stream: words are
     * lowercased and whitespace variants collapse, while boolean operators
     * (AND/OR/NOT) keep their operator identity. Without this, {@code cat AND
     * dog} (boolean AND) and {@code cat and dog} (implicit AND) would collide on
     * the same key.
     */
    private String normalizeQuery(String query) {
        return com.minigoogle.query.lexer.QueryKey.canonicalize(query);
    }
}
