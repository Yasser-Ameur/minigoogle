package com.minigoogle.core.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for LRUCache functionality. */
class LRUCacheTest {

    @Test
    void testPutAndGet() {
        LRUCache<String, Integer> cache = new LRUCache<>(10);
        cache.put("a", 1);
        assertEquals(1, cache.get("a"));
    }

    @Test
    void testGetMissReturnsNull() {
        LRUCache<String, Integer> cache = new LRUCache<>(10);
        assertNull(cache.get("missing"));
    }

    @Test
    void testEvictionWhenFull() {
        LRUCache<String, Integer> cache = new LRUCache<>(3);
        for (int i = 0; i < 5; i++) {
            cache.put("key" + i, i);
        }
        // Capacity 3 -> oldest two keys evicted
        assertNull(cache.get("key0"));
        assertNull(cache.get("key1"));
        assertNotNull(cache.get("key2"));
        assertNotNull(cache.get("key3"));
        assertNotNull(cache.get("key4"));
        assertEquals(3, cache.size());
    }

    @Test
    void testAccessRefreshesRecency() {
        LRUCache<String, Integer> cache = new LRUCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        cache.get("a"); // refresh recency of "a"
        cache.put("d", 4);
        // "b" was least-recently used before the insert, so it should be evicted
        assertNotNull(cache.get("a"));
        assertNull(cache.get("b"));
        assertNotNull(cache.get("c"));
        assertNotNull(cache.get("d"));
    }

    @Test
    void testContainsKey() {
        LRUCache<String, Integer> cache = new LRUCache<>(10);
        assertFalse(cache.containsKey("missing"));
        cache.put("present", 1);
        assertTrue(cache.containsKey("present"));
    }

    @Test
    void testClear() {
        LRUCache<String, Integer> cache = new LRUCache<>(10);
        cache.put("a", 1);
        cache.put("b", 2);
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("a"));
    }
}
