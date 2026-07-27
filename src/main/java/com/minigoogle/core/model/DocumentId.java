package com.minigoogle.core.model;

import java.util.UUID;

public record DocumentId(UUID value) {
    public DocumentId {
        if (value == null) throw new IllegalArgumentException("DocumentId value must not be null");
    }

    public static DocumentId generate() {
        return new DocumentId(UUID.randomUUID());
    }

    public static DocumentId fromString(String uuid) {
        return new DocumentId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
