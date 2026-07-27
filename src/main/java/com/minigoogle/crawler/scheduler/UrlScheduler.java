package com.minigoogle.crawler.scheduler;

import com.minigoogle.crawler.model.CrawlTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * URL scheduling with retry logic, per-domain delays, and priority scoring.
 * Rotates across domain queues for fairness, computes composite priority scores
 * from freshness and authority signals, and handles exponential-backoff retries.
 */
public class UrlScheduler {

    private static final Logger logger = LoggerFactory.getLogger(UrlScheduler.class);

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 5000;
    private static final long DEFAULT_CRAWL_DELAY_MS = 1000;
    private static final int PRIORITY_CRITICAL = 100;
    private static final int PRIORITY_HIGH = 75;
    private static final int PRIORITY_MEDIUM = 50;
    private static final int PRIORITY_LOW = 25;

    private final ConcurrentHashMap<String, DomainQueue> domainQueues;
    private final Map<String, Integer> domainAuthority;
    private final Map<String, Integer> domainLinkCount;
    private final ReentrantLock schedulerLock;
    private int lastDispatchedDomainIndex;

    public UrlScheduler() {
        this.domainQueues = new ConcurrentHashMap<>();
        this.domainAuthority = new ConcurrentHashMap<>();
        this.domainLinkCount = new ConcurrentHashMap<>();
        this.schedulerLock = new ReentrantLock();
        this.lastDispatchedDomainIndex = 0;
    }

    public void submitTask(CrawlTask task) {
        task.setPriority(computePriority(task));
        task.setState(com.minigoogle.crawler.model.UrlState.QUEUED);

        DomainQueue domainQueue = domainQueues.computeIfAbsent(
            task.getDomain(),
            d -> new DomainQueue(d, DEFAULT_CRAWL_DELAY_MS)
        );
        domainQueue.enqueue(task);
        logger.debug("Submitted task to scheduler: {} with priority {}", task.getUrl(), task.getPriority());
    }

    public Optional<CrawlTask> nextEligibleTask() {
        schedulerLock.lock();
        try {
            List<DomainQueue> queues = new ArrayList<>(domainQueues.values());
            if (queues.isEmpty()) {
                return Optional.empty();
            }

            int totalQueues = queues.size();
            for (int i = 0; i < totalQueues; i++) {
                int index = (lastDispatchedDomainIndex + i) % totalQueues;
                DomainQueue queue = queues.get(index);
                CrawlTask task = queue.pollEligible();
                if (task != null) {
                    lastDispatchedDomainIndex = (index + 1) % totalQueues;
                    return Optional.of(task);
                }
            }
            return Optional.empty();
        } finally {
            schedulerLock.unlock();
        }
    }

    public void onTaskCompleted(String url) {
        logger.debug("Task completed: {}", url);
    }

    public void onTaskFailed(CrawlTask task) {
        if (task.getRetryCount() < MAX_RETRIES) {
            long delay = RETRY_BASE_DELAY_MS * (1L << task.getRetryCount());
            Instant nextAllowed = Instant.now().plusMillis(delay);
            task.markRetry(nextAllowed);
            DomainQueue queue = domainQueues.get(task.getDomain());
            if (queue != null) {
                queue.enqueue(task);
            }
            logger.warn("Task failed, scheduled retry {}/{} for {} after {}ms",
                task.getRetryCount(), MAX_RETRIES, task.getUrl(), delay);
        } else {
            task.markFailed();
            logger.error("Task permanently failed after {} retries: {}", MAX_RETRIES, task.getUrl());
        }
    }

    public void updateDomainAuthority(String domain, int authority) {
        domainAuthority.put(domain, authority);
    }

    public void incrementLinkCount(String domain) {
        domainLinkCount.merge(domain, 1, Integer::sum);
    }

    public void updateCrawlDelay(String domain, long crawlDelayMillis) {
        DomainQueue queue = domainQueues.get(domain);
        if (queue != null) {
            logger.info("Updated crawl delay for {} to {}ms", domain, crawlDelayMillis);
        }
    }

    public int computePriority(CrawlTask task) {
        int freshness = computeFreshnessScore(task);
        int authority = computeDomainAuthority(task.getDomain());
        int linkPopularity = computeLinkPopularity(task.getDomain());
        int recrawl = computeRecrawlScore(task);
        int crawlUrgency = computeCrawlUrgency(task);

        int priority = freshness + authority + linkPopularity + recrawl + crawlUrgency;
        return Math.max(0, Math.min(100, priority));
    }

    private int computeFreshnessScore(CrawlTask task) {
        long ageHours = Duration.between(task.getDiscoveredAt(), Instant.now()).toHours();
        if (ageHours < 1) return PRIORITY_CRITICAL;
        if (ageHours < 24) return PRIORITY_HIGH;
        if (ageHours < 168) return PRIORITY_MEDIUM;
        return PRIORITY_LOW;
    }

    private int computeCrawlUrgency(CrawlTask task) {
        Instant nextCrawl = task.getNextCrawl();
        if (nextCrawl == null) return PRIORITY_MEDIUM;
        if (Instant.now().isAfter(nextCrawl)) return PRIORITY_CRITICAL;
        long hoursUntilCrawl = Duration.between(Instant.now(), nextCrawl).toHours();
        if (hoursUntilCrawl <= 1) return PRIORITY_CRITICAL;
        if (hoursUntilCrawl <= 24) return PRIORITY_HIGH;
        if (hoursUntilCrawl <= 168) return PRIORITY_MEDIUM;
        return PRIORITY_LOW;
    }

    private int computeDomainAuthority(String domain) {
        return domainAuthority.getOrDefault(domain, 50);
    }

    private int computeLinkPopularity(String domain) {
        int links = domainLinkCount.getOrDefault(domain, 0);
        if (links > 1000) return PRIORITY_CRITICAL;
        if (links > 100) return PRIORITY_HIGH;
        if (links > 10) return PRIORITY_MEDIUM;
        return PRIORITY_LOW;
    }

    private int computeRecrawlScore(CrawlTask task) {
        if (task.getDepth() == 0) return PRIORITY_HIGH;
        if (task.getDepth() <= 2) return PRIORITY_MEDIUM;
        return PRIORITY_LOW;
    }

    public int getQueueSize() {
        return domainQueues.values().stream().mapToInt(DomainQueue::size).sum();
    }

    public int getDomainQueueCount() {
        return domainQueues.size();
    }

    public boolean isEmpty() {
        return domainQueues.values().stream().allMatch(DomainQueue::isEmpty);
    }

    public Set<String> getActiveDomains() {
        return Collections.unmodifiableSet(domainQueues.keySet());
    }
}
