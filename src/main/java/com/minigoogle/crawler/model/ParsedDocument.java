package com.minigoogle.crawler.model;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Represents a parsed document ready to be sent to the indexer.
 */
public record ParsedDocument(
    UUID id,
    URI url,
    String title,
    String text,
    List<URI> outgoingLinks,
    Instant crawlTime
) {}
