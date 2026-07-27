package com.minigoogle.distributed.query.merge;

import com.minigoogle.network.dto.SearchResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Performs a K-way merge over sorted result lists from multiple shards.
 * Uses a min-heap of size topK to efficiently find the global Top-K
 * without fully sorting all results.
 *
 * <p>Complexity: O(N log K) where N is the total number of results
 * across all shards and K is the desired top-K count.</p>
 */
public class KWayMerger {

    /**
     * Merges multiple sorted result lists into a single globally-sorted Top-K list.
     *
     * @param shardResults List of per-shard result lists, each locally sorted by score descending.
     * @param topK         The number of top results to retain.
     * @return The global Top-K results, sorted by score descending.
     */
    public List<SearchResult> merge(List<List<SearchResult>> shardResults, int topK) {
        if (shardResults == null || shardResults.isEmpty() || topK <= 0) {
            return List.of();
        }

        // Min-heap by score: the smallest-score element sits at the top.
        // When the heap exceeds topK, we evict the smallest, guaranteeing
        // only the top-K highest-scoring results remain.
        PriorityQueue<SearchResult> minHeap = new PriorityQueue<>(
                topK + 1,
                Comparator.comparingDouble(SearchResult::score)
        );

        for (List<SearchResult> results : shardResults) {
            for (SearchResult result : results) {
                minHeap.offer(result);
                if (minHeap.size() > topK) {
                    minHeap.poll(); // evict the lowest score
                }
            }
        }

        // Drain the heap and reverse to get descending order
        List<SearchResult> merged = new ArrayList<>(minHeap.size());
        while (!minHeap.isEmpty()) {
            merged.add(minHeap.poll());
        }
        Collections.reverse(merged);
        return merged;
    }
}
