package com.minigoogle.distributed.query.cache;

import com.minigoogle.network.dto.SearchResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LRU cache for recently merged Top-K results.
 * Popular queries (e.g., "weather", "youtube") are served directly
 * from cache without cluster execution.
 */
public class DistributedQueryCache {

    private final int maxSize;
    private final Map<String, CacheEntry> cache;

    public DistributedQueryCache(int maxSize) {
        this.maxSize = maxSize;
        // LinkedHashMap with accessOrder=true gives LRU semantics
        this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > DistributedQueryCache.this.maxSize;
            }
        };
    }

    /**
     * Looks up cached results for a query.
     * @return The cached results, or null if not found or expired.
     */
    public synchronized List<SearchResult> get(String query) {
        CacheEntry entry = cache.get(normalizeQuery(query));
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            cache.remove(normalizeQuery(query));
            return null;
        }
        return entry.results;
    }

    /**
     * Stores merged Top-K results for a query with a TTL.
     */
    public synchronized void put(String query, List<SearchResult> results, long ttlMs) {
        cache.put(normalizeQuery(query), new CacheEntry(results, System.currentTimeMillis() + ttlMs));
    }

    /**
     * Convenience overload with a default 60-second TTL.
     */
    public void put(String query, List<SearchResult> results) {
        put(query, results, 60_000);
    }

    public synchronized int size() {
        return cache.size();
    }

    public synchronized void clear() {
        cache.clear();
    }

    private String normalizeQuery(String query) {
        return query.strip().toLowerCase();
    }

    private static class CacheEntry {
        final List<SearchResult> results;
        final long expiresAtMs;

        CacheEntry(List<SearchResult> results, long expiresAtMs) {
            this.results = results;
            this.expiresAtMs = expiresAtMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAtMs;
        }
    }
}
