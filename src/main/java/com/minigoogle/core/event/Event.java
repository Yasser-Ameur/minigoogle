package com.minigoogle.core.event;

import java.time.Instant;
import java.util.UUID;

public interface Event {
    UUID eventId();
    Instant timestamp();
    String eventType();
}
