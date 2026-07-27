package com.minigoogle.core.event;

import java.time.Instant;
import java.util.UUID;

public record QueryExecutedEvent(
    UUID eventId,
    Instant timestamp,
    String query,
    int resultCount,
    long durationMs,
    boolean cacheHit
) implements Event {
    public QueryExecutedEvent(String query, int resultCount, long durationMs, boolean cacheHit) {
        this(UUID.randomUUID(), Instant.now(), query, resultCount, durationMs, cacheHit);
    }

    @Override
    public String eventType() {
        return "QUERY_EXECUTED";
    }
}
