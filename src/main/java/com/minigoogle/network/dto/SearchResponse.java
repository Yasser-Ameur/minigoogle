package com.minigoogle.network.dto;

import java.util.List;

/**
 * Standard response payload for the /api/v1/search endpoint.
 *
 * @param executionTimeMs Total time taken to execute the query across the cluster.
 * @param totalResults    Estimated total number of matching documents.
 * @param results         The requested page of results.
 * @param didYouMean      Suggested correction when no results found (may be null).
 * @param maxPageRank     The responding node's corpus-global maximum PageRank,
 *                        used by the coordinator to build the global
 *                        normalization context. 0 for standalone responses.
 * @param maxDocLength    The responding node's corpus-global maximum document
 *                        length. 0 for standalone responses.
 */
public record SearchResponse(
        long executionTimeMs,
        int totalResults,
        List<SearchResult> results,
        String didYouMean,
        double maxPageRank,
        double maxDocLength
) {
    public SearchResponse(long executionTimeMs, int totalResults, List<SearchResult> results, String didYouMean) {
        this(executionTimeMs, totalResults, results, didYouMean, 0.0, 0.0);
    }

    public SearchResponse(long executionTimeMs, int totalResults, List<SearchResult> results) {
        this(executionTimeMs, totalResults, results, null);
    }
}
