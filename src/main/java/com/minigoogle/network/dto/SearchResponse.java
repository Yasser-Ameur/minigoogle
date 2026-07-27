package com.minigoogle.network.dto;

import java.util.List;

/**
 * Standard response payload for the /api/v1/search endpoint.
 *
 * @param executionTimeMs Total time taken to execute the query across the cluster.
 * @param totalResults    Estimated total number of matching documents.
 * @param results         The requested page of results.
 * @param didYouMean      Suggested correction when no results found (may be null).
 */
public record SearchResponse(
        long executionTimeMs,
        int totalResults,
        List<SearchResult> results,
        String didYouMean
) {
    public SearchResponse(long executionTimeMs, int totalResults, List<SearchResult> results) {
        this(executionTimeMs, totalResults, results, null);
    }
}
