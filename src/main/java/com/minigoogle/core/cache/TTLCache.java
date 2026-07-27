package com.minigoogle.core.cache;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TTLCache<K, V> {

    private final int maxSize;
    private final long defaultTtlMs;
    private final Map<K, CacheEntry<V>> cache;

    public TTLCache(int maxSize, long defaultTtlMs) {
        this.maxSize = maxSize;
        this.defaultTtlMs = defaultTtlMs;
        this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, CacheEntry<V>> eldest) {
                return size() > TTLCache.this.maxSize;
            }
        };
    }

    public TTLCache(int maxSize) {
        this(maxSize, 60_000);
    }

    public synchronized V get(K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry == null) return null;
        if (entry.isExpired()) {
            cache.remove(key);
            return null;
        }
        return entry.value;
    }

    public synchronized void put(K key, V value, long ttlMs) {
        cache.put(key, new CacheEntry<>(value, System.currentTimeMillis() + ttlMs));
    }

    public synchronized void put(K key, V value) {
        put(key, value, defaultTtlMs);
    }

    public synchronized boolean containsKey(K key) {
        CacheEntry<V> entry = cache.get(key);
        return entry != null && !entry.isExpired();
    }

    public synchronized int size() {
        return cache.size();
    }

    public synchronized void clear() {
        cache.clear();
    }

    private static class CacheEntry<V> {
        final V value;
        final long expiresAtMs;

        CacheEntry(V value, long expiresAtMs) {
            this.value = value;
            this.expiresAtMs = expiresAtMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAtMs;
        }
    }
}
