package com.minigoogle.crawler.model;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/**
 * Core crawl task model with URL, priority, depth, and state tracking.
 * Encapsulates the full lifecycle of a single URL through the crawl pipeline,
 * from discovery through indexing or failure with retry support.
 */
public class CrawlTask {

    private final URI url;
    private final String domain;
    private final int depth;
    private final Instant discoveredAt;
    private volatile UrlState state;
    private volatile int priority;
    private volatile Instant nextAllowedFetch;
    private volatile Instant nextCrawl;
    private volatile String assignedWorkerId;
    private volatile int retryCount;

    public CrawlTask(URI url, String domain, int depth, Instant discoveredAt) {
        this.url = url;
        this.domain = domain;
        this.depth = depth;
        this.discoveredAt = discoveredAt;
        this.state = UrlState.DISCOVERED;
        this.priority = 0;
        this.nextAllowedFetch = Instant.EPOCH;
        this.nextCrawl = Instant.now().plus(Duration.ofHours(24));
        this.assignedWorkerId = null;
        this.retryCount = 0;
    }

    public URI getUrl() {
        return url;
    }

    public String getDomain() {
        return domain;
    }

    public int getDepth() {
        return depth;
    }

    public Instant getDiscoveredAt() {
        return discoveredAt;
    }

    public UrlState getState() {
        return state;
    }

    public void setState(UrlState state) {
        this.state = state;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public Instant getNextAllowedFetch() {
        return nextAllowedFetch;
    }

    public void setNextAllowedFetch(Instant nextAllowedFetch) {
        this.nextAllowedFetch = nextAllowedFetch;
    }

    public Instant getNextCrawl() {
        return nextCrawl;
    }

    public void setNextCrawl(Instant nextCrawl) {
        this.nextCrawl = nextCrawl;
    }

    public String getAssignedWorkerId() {
        return assignedWorkerId;
    }

    public void setAssignedWorkerId(String workerId) {
        this.assignedWorkerId = workerId;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public boolean canFetchNow() {
        return state == UrlState.QUEUED && Instant.now().isAfter(nextAllowedFetch);
    }

    public void markAssigned(String workerId) {
        this.assignedWorkerId = workerId;
        this.state = UrlState.ASSIGNED;
    }

    public void markFetching() {
        this.state = UrlState.FETCHING;
    }

    public void markFetched() {
        this.state = UrlState.FETCHED;
    }

    public void markIndexed() {
        this.state = UrlState.INDEXED;
    }

    public void markFailed() {
        this.state = UrlState.FAILED;
        this.assignedWorkerId = null;
    }

    public void markRetry(Instant nextAllowedFetch) {
        this.state = UrlState.RETRY;
        this.assignedWorkerId = null;
        this.nextAllowedFetch = nextAllowedFetch;
        this.retryCount++;
    }

    public void requeue() {
        this.state = UrlState.QUEUED;
        this.assignedWorkerId = null;
    }

    @Override
    public String toString() {
        return "CrawlTask{url=" + url + ", state=" + state + ", priority=" + priority + ", domain=" + domain + "}";
    }
}
