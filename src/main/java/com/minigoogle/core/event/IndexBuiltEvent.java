package com.minigoogle.core.event;

import java.time.Instant;
import java.util.UUID;

public record IndexBuiltEvent(
    UUID eventId,
    Instant timestamp,
    int documentCount,
    long durationMs
) implements Event {
    public IndexBuiltEvent(int documentCount, long durationMs) {
        this(UUID.randomUUID(), Instant.now(), documentCount, durationMs);
    }

    @Override
    public String eventType() {
        return "INDEX_BUILT";
    }
}
