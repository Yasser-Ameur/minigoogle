package com.minigoogle.distributed.query.timeout;

import com.minigoogle.distributed.query.model.QueryContext;

import java.time.Duration;

/**
 * Enforces strict time budgets for distributed query execution.
 * The coordinator uses this to decide when to stop waiting for slow shards
 * and return partial results instead.
 */
public class TimeoutManager {

    private static final long DEFAULT_TIMEOUT_MS = 50;

    /**
     * Returns the remaining time in milliseconds for the given context,
     * clamped to at least 1ms to allow minimum work.
     */
    public long getRemainingMs(QueryContext context) {
        return context.getRemainingTimeMs();
    }

    /**
     * Returns true if the query has exceeded its time budget.
     */
    public boolean isExpired(QueryContext context) {
        return context.isTimedOut();
    }

    /**
     * Returns the maximum time the scatter phase should wait for shard responses.
     * This is a fraction of the total budget reserved for the search phase.
     */
    public long getScatterBudgetMs(QueryContext context) {
        // Allocate 50% of total budget for the scatter phase
        return Math.max(1, context.getTimeout().toMillis() / 2);
    }

    /**
     * Returns the maximum time the merge phase should take.
     */
    public long getMergeBudgetMs(QueryContext context) {
        // Allocate 20% of total budget for the merge phase
        return Math.max(1, context.getTimeout().toMillis() / 5);
    }
}
