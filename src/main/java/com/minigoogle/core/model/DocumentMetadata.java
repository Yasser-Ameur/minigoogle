package com.minigoogle.core.model;

import java.net.URI;
import java.time.Instant;

public record DocumentMetadata(
    DocumentId id,
    URI url,
    String title,
    int length,
    Instant timestamp
) {
    public DocumentMetadata {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        if (url == null) throw new IllegalArgumentException("url must not be null");
        if (timestamp == null) throw new IllegalArgumentException("timestamp must not be null");
    }
}
