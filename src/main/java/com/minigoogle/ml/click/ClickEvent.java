package com.minigoogle.ml.click;

import java.time.Instant;

/**
 * A recorded user click on a search result.
 *
 * @param query       The query that was served.
 * @param documentId  The clicked document ID.
 * @param url         The clicked document URL.
 * @param position    The 1-based position the result was served at.
 * @param timestamp   The click time.
 * @param sessionId   An optional client session id for deduplication.
 */
public record ClickEvent(
        String query,
        int documentId,
        String url,
        int position,
        Instant timestamp,
        String sessionId
) {
    public ClickEvent(String query, int documentId, String url, int position) {
        this(query, documentId, url, position, Instant.now(), null);
    }
}
