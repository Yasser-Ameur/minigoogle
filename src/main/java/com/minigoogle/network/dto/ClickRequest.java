package com.minigoogle.network.dto;

/**
 * Payload for the /api/v1/click endpoint.
 *
 * @param query      The query the result was served for.
 * @param documentId The clicked document ID.
 * @param url        The clicked document URL.
 * @param position   The 1-based position the result was served at.
 * @param sessionId  Optional client session id.
 */
public record ClickRequest(
        String query,
        int documentId,
        String url,
        int position,
        String sessionId
) {
}
