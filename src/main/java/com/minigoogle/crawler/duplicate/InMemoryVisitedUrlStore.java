package com.minigoogle.crawler.duplicate;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory visited URL store backed by {@link ConcurrentHashMap}.
 * Provides thread-safe, lock-free deduplication using atomic put-if-absent semantics.
 */
public class InMemoryVisitedUrlStore implements VisitedUrlStore {

    private final ConcurrentHashMap<String, Boolean> visited = new ConcurrentHashMap<>();

    @Override
    public boolean isVisitedOrMark(URI uri) {
        if (uri == null) return true;
        // putIfAbsent returns null if the key was not already associated with a value
        return visited.putIfAbsent(uri.toString(), Boolean.TRUE) != null;
    }
    
    // For testing
    public int size() {
        return visited.size();
    }
}
