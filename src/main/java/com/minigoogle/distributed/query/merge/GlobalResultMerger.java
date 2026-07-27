package com.minigoogle.distributed.query.merge;

import com.minigoogle.distributed.query.model.LocalSearchResponse;
import com.minigoogle.network.dto.SearchResult;

import java.util.ArrayList;
import java.util.List;

/**
 * High-level merge coordinator that takes per-shard LocalSearchResponses
 * and delegates to KWayMerger to produce the global Top-K.
 */
public class GlobalResultMerger {

    private final KWayMerger kWayMerger;

    public GlobalResultMerger() {
        this.kWayMerger = new KWayMerger();
    }

    /**
     * Merges results from multiple shards into a global Top-K.
     *
     * @param responses The per-shard search responses.
     * @param topK      The number of top results to return.
     * @return The globally ranked Top-K results.
     */
    public List<SearchResult> merge(List<LocalSearchResponse> responses, int topK) {
        List<List<SearchResult>> allResults = new ArrayList<>(responses.size());
        int totalHits = 0;

        for (LocalSearchResponse response : responses) {
            if (response != null && response.results() != null) {
                allResults.add(response.results());
                totalHits += response.totalHits();
            }
        }

        return kWayMerger.merge(allResults, topK);
    }

    /**
     * Computes the total number of matching documents across all shards.
     */
    public int computeTotalHits(List<LocalSearchResponse> responses) {
        int total = 0;
        for (LocalSearchResponse response : responses) {
            if (response != null) {
                total += response.totalHits();
            }
        }
        return total;
    }
}
