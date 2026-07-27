package com.minigoogle.network.dto;

/**
 * Standard request payload for the /api/v1/search endpoint.
 *
 * @param query    The search query string.
 * @param page     The page number (1-indexed).
 * @param pageSize Number of results per page.
 */
public record SearchRequest(
        String query,
        int page,
        int pageSize
) {
    public SearchRequest {
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 10;
        if (pageSize > 100) pageSize = 100;
    }
}
