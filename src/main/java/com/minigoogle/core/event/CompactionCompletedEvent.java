package com.minigoogle.core.event;

import java.time.Instant;
import java.util.UUID;

public record CompactionCompletedEvent(
    UUID eventId,
    Instant timestamp,
    int shardId,
    int segmentsBefore,
    int segmentsAfter,
    long durationMs
) implements Event {
    public CompactionCompletedEvent(int shardId, int segmentsBefore, int segmentsAfter, long durationMs) {
        this(UUID.randomUUID(), Instant.now(), shardId, segmentsBefore, segmentsAfter, durationMs);
    }

    @Override
    public String eventType() {
        return "COMPACTION_COMPLETED";
    }
}
