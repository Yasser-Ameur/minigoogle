package com.minigoogle.core.event;

import java.time.Instant;
import java.util.UUID;

public record NodeFailedEvent(
    UUID eventId,
    Instant timestamp,
    String nodeId,
    String reason
) implements Event {
    public NodeFailedEvent(String nodeId, String reason) {
        this(UUID.randomUUID(), Instant.now(), nodeId, reason);
    }

    @Override
    public String eventType() {
        return "NODE_FAILED";
    }
}
