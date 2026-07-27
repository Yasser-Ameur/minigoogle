package com.minigoogle.distributed.query.execution;

import com.minigoogle.distributed.query.model.LocalSearchResponse;
import com.minigoogle.distributed.query.model.QueryContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Submits queries to all relevant shards in parallel using an ExecutorService.
 * Implements the "scatter" phase of scatter-gather: every shard search is launched
 * simultaneously so total latency equals max(shard latency), not sum(shard latencies).
 */
public class DistributedExecutor {

    private final ExecutorService executor;

    /**
     * @param parallelism The number of threads in the query execution pool.
     */
    public DistributedExecutor(int parallelism) {
        this.executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "query-executor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Scatters the query to all provided local executors in parallel.
     * Waits up to the specified timeout for all results.
     * Shards that do not respond in time are ignored (partial results returned).
     *
     * @param shardExecutors The per-shard local executors.
     * @param context        The query context with timeout info.
     * @param timeoutMs      Maximum time to wait for shard responses.
     * @return Collected results from all shards that responded in time.
     */
    public List<LocalSearchResponse> scatter(List<LocalSearchExecutor> shardExecutors,
                                              QueryContext context,
                                              long timeoutMs) {
        List<Future<LocalSearchResponse>> futures = new ArrayList<>(shardExecutors.size());

        // Launch all shard searches simultaneously
        for (LocalSearchExecutor shardExecutor : shardExecutors) {
            futures.add(executor.submit(() -> shardExecutor.execute(context)));
        }

        // Gather results with timeout tolerance
        List<LocalSearchResponse> responses = new ArrayList<>(shardExecutors.size());
        for (Future<LocalSearchResponse> future : futures) {
            try {
                LocalSearchResponse response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                responses.add(response);
            } catch (TimeoutException e) {
                future.cancel(true); // Cancel slow shard
                System.err.println("[DistributedExecutor] Shard timed out after " + timeoutMs + "ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
            } catch (ExecutionException e) {
                System.err.println("[DistributedExecutor] Shard execution failed: " + e.getCause().getMessage());
            }
        }

        return responses;
    }

    /**
     * Gracefully shuts down the executor.
     */
    public void shutdown() {
        executor.shutdown();
    }
}
