package com.minigoogle.storage.cache;

import com.minigoogle.indexer.inverted.PostingList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU cache for frequently accessed posting lists.
 * Backed by a thread-safe {@link LinkedHashMap} with access-order eviction.
 */
public class PostingCache {
    private final Map<String, PostingList> cache;

    public PostingCache(int capacity) {
        this.cache = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PostingList> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized void put(String term, PostingList postingList) {
        cache.put(term, postingList);
    }

    public synchronized PostingList get(String term) {
        return cache.get(term);
    }
    
    public synchronized boolean containsKey(String term) {
        return cache.containsKey(term);
    }

    public synchronized int size() {
        return cache.size();
    }

    public synchronized void clear() {
        cache.clear();
    }
}
