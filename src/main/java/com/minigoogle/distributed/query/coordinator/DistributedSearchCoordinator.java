package com.minigoogle.distributed.query.coordinator;

import com.minigoogle.cluster.transport.SearchTransport;
import com.minigoogle.distributed.query.cache.DistributedQueryCache;
import com.minigoogle.distributed.query.execution.DistributedExecutor;
import com.minigoogle.distributed.query.execution.LocalSearchExecutor;
import com.minigoogle.distributed.query.execution.RemoteSearchExecutor;
import com.minigoogle.distributed.query.execution.SearchExecutor;
import com.minigoogle.distributed.query.merge.GlobalResultMerger;
import com.minigoogle.distributed.query.model.LocalSearchResponse;
import com.minigoogle.distributed.query.model.QueryContext;
import com.minigoogle.distributed.query.routing.QueryRouter;
import com.minigoogle.distributed.query.timeout.TimeoutManager;
import com.minigoogle.network.dto.SearchResult;
import com.minigoogle.network.dto.SearchResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The stateless orchestrator for distributed search.
 * Implements the full scatter-gather pipeline:
 *
 * <ol>
 *   <li>Check cache for identical recent queries</li>
 *   <li>Create QueryContext with request ID and timeout</li>
 *   <li>Scatter query to all shard executors in parallel</li>
 *   <li>Gather results with timeout tolerance</li>
 *   <li>Merge into global Top-K via KWayMerger</li>
 *   <li>Cache the result for future identical queries</li>
 *   <li>Return SearchResponse</li>
 * </ol>
 */
public class DistributedSearchCoordinator {

    private final DistributedExecutor executor;
    private final GlobalResultMerger merger;
    private final TimeoutManager timeoutManager;
    private final DistributedQueryCache cache;
    
    private final QueryRouter router;
    private final SearchTransport transport;
    private final List<LocalSearchExecutor> localExecutors;
    private final String localNodeId;
    private final Duration defaultTimeout;

    public DistributedSearchCoordinator(
            QueryRouter router,
            SearchTransport transport,
            List<LocalSearchExecutor> localExecutors,
            String localNodeId,
            int parallelism,
            Duration defaultTimeout,
            int cacheSize
    ) {
        this.router = router;
        this.transport = transport;
        this.localExecutors = localExecutors;
        this.localNodeId = localNodeId;
        
        this.executor = new DistributedExecutor(parallelism);
        this.merger = new GlobalResultMerger();
        this.timeoutManager = new TimeoutManager();
        this.cache = new DistributedQueryCache(cacheSize);
        this.defaultTimeout = defaultTimeout;
    }

    /**
     * Executes a distributed search query.
     *
     * @param query The raw query string.
     * @param topK  The number of top results to return.
     * @return A SearchResponse with the global Top-K results.
     */
    public SearchResponse search(String query, int topK) {
        long startMs = System.currentTimeMillis();

        // 1. Check cache
        List<SearchResult> cached = cache.get(query);
        if (cached != null) {
            long elapsed = System.currentTimeMillis() - startMs;
            return new SearchResponse(elapsed, cached.size(), cached);
        }

        // 2. Create query context
        QueryContext context = new QueryContext(query, topK, defaultTimeout);

        // 3. Resolve targets and create executors
        List<String> targetNodeIds = router.resolveTargets(query);
        List<SearchExecutor> targetExecutors = new ArrayList<>();
        for (String targetNodeId : targetNodeIds) {
            if (targetNodeId.equals(localNodeId)) {
                targetExecutors.addAll(localExecutors);
            } else {
                targetExecutors.add(new RemoteSearchExecutor(targetNodeId, transport));
            }
        }

        // 4. Scatter to all shards with timeout
        long scatterBudget = timeoutManager.getScatterBudgetMs(context);
        List<LocalSearchResponse> shardResponses = executor.scatter(targetExecutors, context, scatterBudget);

        // 5. Merge results
        List<SearchResult> mergedResults = merger.merge(shardResponses, topK);
        int totalHits = merger.computeTotalHits(shardResponses);

        // 6. Cache the result
        cache.put(query, mergedResults);

        long elapsed = System.currentTimeMillis() - startMs;
        return new SearchResponse(elapsed, totalHits, mergedResults);
    }

    /**
     * Gracefully shuts down the coordinator's thread pool.
     */
    public void shutdown() {
        executor.shutdown();
    }
}
