package com.minigoogle.distributed.query.model;

import com.minigoogle.network.dto.SearchResult;

import java.util.List;

/**
 * Wraps the local Top-K results returned from a single shard.
 *
 * @param shardId     The shard that produced these results.
 * @param results     The local Top-K ranked results.
 * @param totalHits   Total number of matching documents on this shard (before Top-K).
 * @param latencyMs   Time taken on this shard in milliseconds.
 */
public record LocalSearchResponse(
        int shardId,
        List<SearchResult> results,
        int totalHits,
        long latencyMs
) {
}
