package com.minigoogle.crawler.scheduler;

import com.minigoogle.crawler.model.CrawlTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-domain priority queue with politeness buckets for crawl delay.
 * Divides tasks into critical/high/medium/low buckets and enforces a minimum
 * interval between requests to the same domain for respectful crawling.
 */
public class DomainQueue {

    private static final Logger logger = LoggerFactory.getLogger(DomainQueue.class);

    private static final int BUCKET_CRITICAL_MIN = 90;
    private static final int BUCKET_HIGH_MIN = 60;
    private static final int BUCKET_MEDIUM_MIN = 30;

    private final String domain;
    private final LinkedList<CrawlTask>[] buckets;
    private final ReentrantLock lock;
    private volatile long crawlDelayMillis;
    private Instant lastFetchTime;

    @SuppressWarnings("unchecked")
    public DomainQueue(String domain, long defaultCrawlDelayMillis) {
        this.domain = domain;
        this.crawlDelayMillis = defaultCrawlDelayMillis;
        this.buckets = new LinkedList[4];
        for (int i = 0; i < 4; i++) {
            this.buckets[i] = new LinkedList<>();
        }
        this.lock = new ReentrantLock();
        this.lastFetchTime = Instant.EPOCH;
    }

    public void enqueue(CrawlTask task) {
        lock.lock();
        try {
            int bucket = getBucketIndex(task.getPriority());
            buckets[bucket].addLast(task);
            logger.debug("Added task to domain queue for {} (bucket {}): {} (queue size: {})",
                domain, bucketName(bucket), task.getUrl(), size());
        } finally {
            lock.unlock();
        }
    }

    public CrawlTask pollEligible() {
        lock.lock();
        try {
            Instant now = Instant.now();
            long elapsed = Duration.between(lastFetchTime, now).toMillis();

            if (elapsed < crawlDelayMillis) {
                return null;
            }

            for (int i = 0; i < 4; i++) {
                LinkedList<CrawlTask> bucket = buckets[i];
                if (bucket.isEmpty()) continue;

                java.util.List<CrawlTask> deferred = new java.util.ArrayList<>();
                while (!bucket.isEmpty()) {
                    CrawlTask task = bucket.pollFirst();
                    if (!task.getNextAllowedFetch().isAfter(now)) {
                        if (!deferred.isEmpty()) {
                            bucket.addAll(0, deferred);
                        }
                        lastFetchTime = now;
                        logger.debug("Dispatched task from domain {}: {} (bucket: {}, remaining: {})",
                            domain, task.getUrl(), bucketName(i), size());
                        return task;
                    }
                    deferred.add(task);
                }
                if (!deferred.isEmpty()) {
                    bucket.addAll(deferred);
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    public void setCrawlDelayMillis(long crawlDelayMillis) {
        this.crawlDelayMillis = crawlDelayMillis;
        logger.debug("Set crawl delay for {} to {}ms", domain, crawlDelayMillis);
    }

    public long getRemainingDelayMillis() {
        lock.lock();
        try {
            long elapsed = Duration.between(lastFetchTime, Instant.now()).toMillis();
            return Math.max(0, crawlDelayMillis - elapsed);
        } finally {
            lock.unlock();
        }
    }

    public String getDomain() {
        return domain;
    }

    public int size() {
        lock.lock();
        try {
            int total = 0;
            for (LinkedList<CrawlTask> bucket : buckets) {
                total += bucket.size();
            }
            return total;
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            for (LinkedList<CrawlTask> bucket : buckets) {
                if (!bucket.isEmpty()) return false;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public Instant getLastFetchTime() {
        return lastFetchTime;
    }

    private int getBucketIndex(int priority) {
        if (priority >= BUCKET_CRITICAL_MIN) return 0;
        if (priority >= BUCKET_HIGH_MIN) return 1;
        if (priority >= BUCKET_MEDIUM_MIN) return 2;
        return 3;
    }

    private String bucketName(int index) {
        return switch (index) {
            case 0 -> "Critical";
            case 1 -> "High";
            case 2 -> "Medium";
            case 3 -> "Low";
            default -> "Unknown";
        };
    }
}
