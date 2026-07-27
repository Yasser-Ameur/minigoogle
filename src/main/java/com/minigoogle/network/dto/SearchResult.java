package com.minigoogle.network.dto;

/**
 * Standard representation of a single search result.
 */
public record SearchResult(
        String url,
        String title,
        String snippet,
        double score,
        double bm25Score,
        double pageRankScore
) {
}
