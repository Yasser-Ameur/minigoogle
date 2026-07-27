package com.minigoogle.distributed.query.execution;

import com.minigoogle.distributed.query.model.LocalSearchResponse;
import com.minigoogle.distributed.query.model.QueryContext;
import com.minigoogle.network.dto.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BiFunction;

/**
 * Executes a search query locally on a single shard/index.
 * Computes the local Top-K using a bounded priority queue.
 *
 * <p>This executor is designed to run on each index node.
 * It receives a query, executes it against the local index,
 * and returns only the Top-K results to minimize network transfer.</p>
 */
public class LocalSearchExecutor {

    /**
     * Functional interface for the actual search implementation.
     * Takes a query string and topK, returns scored results.
     */
    private final BiFunction<String, Integer, List<SearchResult>> searchFunction;
    private final int shardId;

    /**
     * @param shardId        The ID of the shard this executor operates on.
     * @param searchFunction The function that performs the actual index lookup and scoring.
     */
    public LocalSearchExecutor(int shardId, BiFunction<String, Integer, List<SearchResult>> searchFunction) {
        this.shardId = shardId;
        this.searchFunction = searchFunction;
    }

    /**
     * Executes the query locally and returns a LocalSearchResponse
     * containing the Top-K results from this shard.
     */
    public LocalSearchResponse execute(QueryContext context) {
        long start = System.currentTimeMillis();

        List<SearchResult> allResults = searchFunction.apply(context.getQuery(), context.getTopK());

        // Apply local Top-K via bounded min-heap
        List<SearchResult> topK = computeTopK(allResults, context.getTopK());

        long latency = System.currentTimeMillis() - start;
        return new LocalSearchResponse(shardId, topK, allResults.size(), latency);
    }

    /**
     * Extracts the Top-K results from the full candidate set using a bounded min-heap.
     * Complexity: O(n log k)
     */
    private List<SearchResult> computeTopK(List<SearchResult> candidates, int k) {
        if (candidates.size() <= k) {
            // Already small enough, just sort descending
            List<SearchResult> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator.comparingDouble(SearchResult::score).reversed());
            return sorted;
        }

        PriorityQueue<SearchResult> minHeap = new PriorityQueue<>(
                k + 1,
                Comparator.comparingDouble(SearchResult::score)
        );

        for (SearchResult result : candidates) {
            minHeap.offer(result);
            if (minHeap.size() > k) {
                minHeap.poll();
            }
        }

        List<SearchResult> topK = new ArrayList<>(minHeap.size());
        while (!minHeap.isEmpty()) {
            topK.add(minHeap.poll());
        }
        // Reverse for descending score order
        topK.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return topK;
    }
}
