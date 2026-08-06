package com.minigoogle.distributed.query.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Context object that travels through the entire distributed query pipeline.
 * Enables logging, tracing, cancellation, and timeout enforcement.
 */
public class QueryContext {

    private final UUID requestId;
    private final Instant startTime;
    private final Duration timeout;
    private final String query;
    private final int topK;

    public QueryContext(String query, int topK, Duration timeout) {
        this(query, topK, timeout, UUID.randomUUID());
    }

    /**
     * Creates a query context with an explicit request ID.
     *
     * <p>Used by remote shard nodes that receive a dispatched query: the
     * coordinator's request ID is carried on the wire so the entire
     * scatter-gather fan-out shares one trace ID. The start time is reset
     * locally so the remaining-time budget is computed against the
     * receiving node's own clock.
     *
     * @param query     The raw query string.
     * @param topK      The number of top results to return.
     * @param timeout   The time budget for this shard's execution.
     * @param requestId The traceable request ID (typically from the coordinator).
     */
    public QueryContext(String query, int topK, Duration timeout, UUID requestId) {
        this.requestId = requestId;
        this.startTime = Instant.now();
        this.timeout = timeout;
        this.query = query;
        this.topK = topK;
    }

    public QueryContext(String query, int topK) {
        this(query, topK, Duration.ofMillis(50));
    }

    public UUID getRequestId() {
        return requestId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public String getQuery() {
        return query;
    }

    public int getTopK() {
        return topK;
    }

    /**
     * @return The remaining time budget in milliseconds, or 0 if already exceeded.
     */
    public long getRemainingTimeMs() {
        long elapsed = Duration.between(startTime, Instant.now()).toMillis();
        long remaining = timeout.toMillis() - elapsed;
        return Math.max(0, remaining);
    }

    /**
     * @return true if the time budget has been exceeded.
     */
    public boolean isTimedOut() {
        return getRemainingTimeMs() <= 0;
    }
}
