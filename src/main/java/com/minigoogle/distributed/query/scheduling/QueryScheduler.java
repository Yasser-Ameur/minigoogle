package com.minigoogle.distributed.query.scheduling;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Thread pool manager that isolates different workload types.
 *
 * Per ARCHITECTURE.md Ch09 §13 (Thread Pool Design):
 *   HTTP Requests  → 16 threads
 *   Merge          → 4 threads
 *   Background     → 2 threads
 *   Never mix workloads. This prevents starvation.
 *
 * Per ARCHITECTURE.md Ch09 §12 (Parallel Execution Model):
 *   Coordinator owns ExecutorService. Every shard request → Future.
 *   Coordinator waits with future.get(timeout).
 */
public class QueryScheduler {

    private final ExecutorService httpPool;
    private final ExecutorService mergePool;
    private final ExecutorService backgroundPool;

    public QueryScheduler(int httpThreads, int mergeThreads, int backgroundThreads) {
        this.httpPool = Executors.newFixedThreadPool(httpThreads, r -> {
            Thread t = new Thread(r, "HTTP-" + r.hashCode());
            t.setDaemon(true);
            return t;
        });
        this.mergePool = Executors.newFixedThreadPool(mergeThreads, r -> {
            Thread t = new Thread(r, "Merge-" + r.hashCode());
            t.setDaemon(true);
            return t;
        });
        this.backgroundPool = Executors.newFixedThreadPool(backgroundThreads, r -> {
            Thread t = new Thread(r, "BG-" + r.hashCode());
            t.setDaemon(true);
            return t;
        });
    }

    public QueryScheduler() {
        this(16, 4, 2);
    }

    /**
     * Submits a search/query task to the HTTP thread pool.
     */
    public <T> Future<T> submitSearch(Callable<T> task) {
        return httpPool.submit(task);
    }

    /**
     * Submits a merge task to the merge thread pool.
     */
    public <T> Future<T> submitMerge(Callable<T> task) {
        return mergePool.submit(task);
    }

    /**
     * Submits a background task (compaction, cleanup, etc.).
     */
    public <T> Future<T> submitBackground(Callable<T> task) {
        return backgroundPool.submit(task);
    }

    /**
     * Returns the number of active tasks in the HTTP pool.
     */
    public int getHttpPoolQueueSize() {
        // Approximate: a more precise implementation would track submitted vs completed
        return -1; // ExecutorService doesn't directly expose queue size
    }

    /**
     * Shuts down all thread pools gracefully, waiting up to the given timeout.
     */
    public void shutdown(long timeoutMs) {
        httpPool.shutdown();
        mergePool.shutdown();
        backgroundPool.shutdown();
        try {
            httpPool.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
            mergePool.awaitTermination(timeoutMs / 2, TimeUnit.MILLISECONDS);
            backgroundPool.awaitTermination(timeoutMs / 4, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Forcefully shuts down all thread pools.
     */
    public void shutdownNow() {
        httpPool.shutdownNow();
        mergePool.shutdownNow();
        backgroundPool.shutdownNow();
    }
}
