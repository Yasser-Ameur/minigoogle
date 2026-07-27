package com.minigoogle.core.event;

import java.time.Instant;
import java.util.UUID;

public record NodeJoinedEvent(
    UUID eventId,
    Instant timestamp,
    String nodeId,
    String host,
    int port
) implements Event {
    public NodeJoinedEvent(String nodeId, String host, int port) {
        this(UUID.randomUUID(), Instant.now(), nodeId, host, port);
    }

    @Override
    public String eventType() {
        return "NODE_JOINED";
    }
}
