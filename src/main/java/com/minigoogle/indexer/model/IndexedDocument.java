package com.minigoogle.indexer.model;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents metadata of a document that is being indexed.
 */
public record IndexedDocument(
    UUID id,
    URI url,
    String title,
    int length,
    Instant timestamp
) {}
