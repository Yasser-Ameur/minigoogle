package com.minigoogle.crawler.model;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * Represents a webpage successfully downloaded by the downloader.
 */
public record DownloadedPage(
    URI uri,
    int statusCode,
    String html,
    Map<String, String> headers,
    Instant downloadTime
) {}
