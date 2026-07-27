package com.minigoogle.storage.cache;

import java.util.LinkedHashMap;
import java.util.Map;

public class DictionaryCache {

    private final int maxSize;
    private final Map<String, DictionaryEntry> cache;

    public DictionaryCache(int maxSize) {
        this.maxSize = maxSize;
        this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, DictionaryEntry> eldest) {
                return size() > DictionaryCache.this.maxSize;
            }
        };
    }

    public DictionaryEntry get(String term) {
        return cache.get(term);
    }

    public void put(String term, DictionaryEntry entry) {
        cache.put(term, entry);
    }

    public void evict() {
        if (!cache.isEmpty()) {
            var iterator = cache.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }
}
