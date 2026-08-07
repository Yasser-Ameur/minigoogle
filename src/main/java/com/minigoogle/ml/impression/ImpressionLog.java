package com.minigoogle.ml.impression;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores the most recent served impression per normalized query.
 *
 * <p>This is the coordinator's counterpart to a shard's corpus: it is the
 * record of what the coordinator actually served, so a later click can be
 * attributed to the exact result ordering, normalization context and feature
 * vectors that were served. This is what lets the coordinator's
 * {@link ServedImpressionFeatureProvider} satisfy the "train-time features
 * equal serve-time features" invariant without owning any documents.</p>
 */
public class ImpressionLog {

    private final Map<String, ServedImpression> byQuery = new ConcurrentHashMap<>();
    private final long capacity;

    public ImpressionLog() {
        this(10_000);
    }

    public ImpressionLog(long capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /**
     * Stores an impression, replacing any previous impression for the query.
     */
    public void recordImpression(ServedImpression impression) {
        if (impression == null || impression.query() == null) {
            return;
        }
        String key = normalize(impression.query());
        if (!byQuery.containsKey(key) && byQuery.size() >= capacity) {
            return;
        }
        byQuery.put(key, impression);
    }

    /**
     * Returns the most recent impression for a query, or {@code null}.
     */
    public ServedImpression impression(String query) {
        return query == null ? null : byQuery.get(normalize(query));
    }

    public long size() {
        return byQuery.size();
    }

    public void clear() {
        byQuery.clear();
    }

    private static String normalize(String query) {
        return query == null ? "" : query.toLowerCase().strip();
    }
}
