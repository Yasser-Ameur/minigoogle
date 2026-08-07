package com.minigoogle.ml.impression;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maps document URLs to coordinator-global integer ids.
 *
 * <p>Shard nodes address documents by shard-local ids (each shard numbers its
 * own corpus from 1), so an id is not comparable across nodes. The URL is the
 * cross-node identity; this registry is where the coordinator assigns a global
 * id per unique URL so clicks and impressions can reference a single document
 * regardless of which shard serves it.</p>
 */
public class DocIdRegistry {

    private final Map<String, Integer> idByUrl = new ConcurrentHashMap<>();
    private final Map<Integer, String> urlById = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    /**
     * Returns the coordinator-global id for a URL, assigning a new one on first
     * sight. Returns {@code -1} for a null URL.
     */
    public int resolve(String url) {
        if (url == null) {
            return -1;
        }
        Integer existing = idByUrl.get(url);
        if (existing != null) {
            return existing;
        }
        int id = nextId.getAndIncrement();
        Integer raced = idByUrl.putIfAbsent(url, id);
        if (raced != null) {
            return raced;
        }
        urlById.put(id, url);
        return id;
    }

    /**
     * Returns the URL registered for a global id, or {@code null}.
     */
    public String url(int docId) {
        return urlById.get(docId);
    }

    /**
     * The number of unique URLs resolved so far.
     */
    public int size() {
        return idByUrl.size();
    }
}
