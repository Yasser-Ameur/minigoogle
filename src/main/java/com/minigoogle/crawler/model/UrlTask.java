package com.minigoogle.crawler.model;

import java.time.Instant;

/**
 * Represents a single URL to be crawled by the system.
 */
public record UrlTask(
    String normalizedUrl,
    String domain,
    int depth,
    Instant discoveredAt
) {}
