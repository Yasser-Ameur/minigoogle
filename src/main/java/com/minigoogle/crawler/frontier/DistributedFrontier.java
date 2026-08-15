package com.minigoogle.crawler.frontier;

import com.minigoogle.crawler.bloom.BloomFilter;
import com.minigoogle.crawler.heartbeat.WorkerHeartbeat;
import com.minigoogle.crawler.model.CrawlTask;
import com.minigoogle.crawler.model.UrlState;
import com.minigoogle.crawler.scheduler.UrlScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Distributed crawl frontier with bloom filter deduplication and worker coordination.
 * Manages the global URL task registry, dispatches work to workers via heartbeat-aware
 * assignment, and tracks enqueued, assigned, completed, and failed task statistics.
 */
public class DistributedFrontier {

    private static final Logger logger = LoggerFactory.getLogger(DistributedFrontier.class);

    /** Default cap on the number of task metadata entries retained in the registry. */
    public static final int DEFAULT_MAX_REGISTRY_SIZE = 1_000_000;

    private final BloomFilter bloomFilter;
    private final UrlScheduler scheduler;
    private final ConcurrentHashMap<String, CrawlTask> taskRegistry;
    private final ConcurrentHashMap<String, WorkerHeartbeat> workerHeartbeats;
    private final ReentrantLock assignmentLock;
    private final long heartbeatTimeoutMillis;
    private final int maxRegistrySize;

    // LongAdder, not volatile long: these are incremented concurrently by every
    // crawler worker thread, and `volatile` provides visibility but NOT atomicity
    // for a read-modify-write. Two threads could read the same value and both
    // write back value+1, silently losing an increment -- observed as a
    // duplicate count of 14 where 16 racing enqueues must produce 15.
    private final LongAdder totalEnqueued = new LongAdder();
    private final LongAdder totalDuplicates = new LongAdder();
    private final LongAdder totalAssigned = new LongAdder();
    private final LongAdder totalCompleted = new LongAdder();
    private final LongAdder totalFailed = new LongAdder();
    private volatile java.util.function.Function<URI, Instant> recrawlPolicy;

    public DistributedFrontier(int bloomFilterExpectedElements, double bloomFilterFalsePositiveRate, long heartbeatTimeoutMillis) {
        this(bloomFilterExpectedElements, bloomFilterFalsePositiveRate, heartbeatTimeoutMillis, DEFAULT_MAX_REGISTRY_SIZE);
    }

    public DistributedFrontier(int bloomFilterExpectedElements, double bloomFilterFalsePositiveRate, long heartbeatTimeoutMillis, int maxRegistrySize) {
        this.bloomFilter = new BloomFilter(bloomFilterExpectedElements, bloomFilterFalsePositiveRate);
        this.scheduler = new UrlScheduler();
        this.taskRegistry = new ConcurrentHashMap<>();
        this.workerHeartbeats = new ConcurrentHashMap<>();
        this.assignmentLock = new ReentrantLock();
        this.heartbeatTimeoutMillis = heartbeatTimeoutMillis;
        this.maxRegistrySize = maxRegistrySize;
    }

    public boolean addUrl(URI url, int depth) {
        String urlString = url.toString();
        String domain = url.getHost().toLowerCase();

        if (bloomFilter.probablyContains(urlString)) {
            totalDuplicates.increment();
            logger.debug("Duplicate URL rejected: {}", url);
            return false;
        }

        bloomFilter.add(urlString);

        CrawlTask task = new CrawlTask(url, domain, depth, Instant.now());
        CrawlTask existing = taskRegistry.putIfAbsent(urlString, task);
        if (existing != null) {
            totalDuplicates.increment();
            logger.debug("Duplicate URL rejected (concurrent enqueue): {}", url);
            return false;
        }

        scheduler.submitTask(task);
        totalEnqueued.increment();

        evictToLimit();

        logger.debug("Enqueued URL: {} (total: {}, duplicates: {})", url, totalEnqueued.sum(), totalDuplicates.sum());
        return true;
    }

    /**
     * Bounds the task registry to {@code maxRegistrySize} by evicting the oldest
     * completed (INDEXED/FETCHED/FAILED) tasks. Deduplication is preserved
     * independently by the bloom filter, so evicted URLs are never re-enqueued;
     * the trade-off is that evicted completed tasks are no longer eligible for
     * recrawl scheduling. Active (QUEUED/RETRY/ASSIGNED) tasks are never evicted.
     */
    public void evictToLimit() {
        if (taskRegistry.size() <= maxRegistrySize) {
            return;
        }
        long toEvict = taskRegistry.size() - maxRegistrySize;
        List<Map.Entry<String, CrawlTask>> evictionCandidates = new ArrayList<>();
        for (Map.Entry<String, CrawlTask> entry : taskRegistry.entrySet()) {
            UrlState state = entry.getValue().getState();
            if (state == UrlState.INDEXED || state == UrlState.FETCHED || state == UrlState.FAILED) {
                evictionCandidates.add(entry);
            }
        }
        evictionCandidates.sort(Comparator.comparing(e -> e.getValue().getDiscoveredAt()));
        long evicted = 0;
        for (Map.Entry<String, CrawlTask> entry : evictionCandidates) {
            if (evicted >= toEvict || taskRegistry.size() <= maxRegistrySize) {
                break;
            }
            taskRegistry.remove(entry.getKey(), entry.getValue());
            evicted++;
        }
        if (evicted > 0) {
            logger.info("Evicted {} completed task(s); registry size now {}", evicted, taskRegistry.size());
        }
        if (taskRegistry.size() > maxRegistrySize) {
            logger.warn("Registry still exceeds limit ({}) with no more completed tasks to evict", maxRegistrySize);
        }
    }

    public boolean isDuplicate(URI url) {
        return bloomFilter.probablyContains(url.toString());
    }

    public Optional<CrawlTask> requestWork(String workerId) {
        assignmentLock.lock();
        try {
            Optional<CrawlTask> taskOpt = scheduler.nextEligibleTask();
            if (taskOpt.isEmpty()) {
                int resubmitted = scheduler.resubmitDueRecrawls(taskRegistry);
                if (resubmitted > 0) {
                    taskOpt = scheduler.nextEligibleTask();
                }
            }
            if (taskOpt.isEmpty()) {
                return Optional.empty();
            }

            CrawlTask task = taskOpt.get();
            if (task.getState() != UrlState.QUEUED && task.getState() != UrlState.RETRY) {
                return Optional.empty();
            }

            task.markAssigned(workerId);
            totalAssigned.increment();

            WorkerHeartbeat heartbeat = workerHeartbeats.get(workerId);
            if (heartbeat != null) {
                heartbeat.startTask(task);
            }

            logger.debug("Assigned work to {}: {} (priority: {})", workerId, task.getUrl(), task.getPriority());
            return Optional.of(task);
        } finally {
            assignmentLock.unlock();
        }
    }

    public void completeTask(String urlString) {
        CrawlTask task = taskRegistry.get(urlString);
        if (task != null) {
            task.markIndexed();
            if (recrawlPolicy != null) {
                task.setNextCrawl(recrawlPolicy.apply(task.getUrl()));
            }
            totalCompleted.increment();

            String workerId = task.getAssignedWorkerId();
            WorkerHeartbeat heartbeat = workerId != null ? workerHeartbeats.get(workerId) : null;
            if (heartbeat != null) {
                heartbeat.completeTask();
            }

            scheduler.onTaskCompleted(urlString);
            logger.debug("Task completed: {}", urlString);

            evictToLimit();
        }
    }

    public void failTask(String urlString, String workerId) {
        CrawlTask task = taskRegistry.get(urlString);
        if (task != null) {
            scheduler.onTaskFailed(task);
            totalFailed.increment();

            WorkerHeartbeat heartbeat = workerHeartbeats.get(workerId);
            if (heartbeat != null) {
                heartbeat.failTask();
            }

            logger.debug("Task failed: {} (retries: {})", urlString, task.getRetryCount());
        }
    }

    public void registerWorkerHeartbeat(WorkerHeartbeat heartbeat) {
        workerHeartbeats.put(heartbeat.getWorkerId(), heartbeat);
        logger.info("Registered worker heartbeat: {}", heartbeat.getWorkerId());
    }

    public List<CrawlTask> recoverFailedWorkerTasks(String workerId) {
        List<CrawlTask> recoveredTasks = new ArrayList<>();
        WorkerHeartbeat heartbeat = workerHeartbeats.remove(workerId);

        if (heartbeat != null) {
            CrawlTask currentTask = heartbeat.getCurrentTask();
            if (currentTask != null) {
                currentTask.requeue();
                scheduler.submitTask(currentTask);
                recoveredTasks.add(currentTask);
                logger.warn("Recovered task from failed worker {}: {}", workerId, currentTask.getUrl());
            }
        }

        for (CrawlTask task : taskRegistry.values()) {
            if (task.getState() == UrlState.ASSIGNED && workerId.equals(task.getAssignedWorkerId())) {
                task.requeue();
                scheduler.submitTask(task);
                recoveredTasks.add(task);
                logger.warn("Recovered orphaned task from worker {}: {}", workerId, task.getUrl());
            }
        }

        return recoveredTasks;
    }

    public List<String> checkWorkerHealth() {
        List<String> failedWorkers = new ArrayList<>();
        for (Map.Entry<String, WorkerHeartbeat> entry : workerHeartbeats.entrySet()) {
            WorkerHeartbeat heartbeat = entry.getValue();
            if (!heartbeat.isAlive()) {
                long deadTime = heartbeat.timeSinceLastHeartbeat().toMillis();
                failedWorkers.add(entry.getKey());
                logger.warn("Worker {} heartbeat timeout ({}ms since last heartbeat)",
                    entry.getKey(), deadTime);
            }
        }
        return failedWorkers;
    }

    public BloomFilter getBloomFilter() {
        return bloomFilter;
    }

    public UrlScheduler getScheduler() {
        return scheduler;
    }

    public Map<String, CrawlTask> getTaskRegistry() {
        return taskRegistry;
    }

    public ConcurrentHashMap<String, WorkerHeartbeat> getWorkerHeartbeats() {
        return workerHeartbeats;
    }

    public long getHeartbeatTimeoutMillis() {
        return heartbeatTimeoutMillis;
    }

    public void restoreTask(CrawlTask task) {
        taskRegistry.put(task.getUrl().toString(), task);
    }

    /**
     * Re-enters restored QUEUED/RETRY tasks into the scheduler so they can be
     * dispatched after a restart. Snapshot state and priority are preserved;
     * per-task {@code nextAllowedFetch} backoff is still honored at dispatch.
     */
    public void rehydrateScheduler() {
        int requeued = 0;
        for (CrawlTask task : taskRegistry.values()) {
            UrlState state = task.getState();
            if (state == UrlState.QUEUED || state == UrlState.RETRY) {
                scheduler.requeueRestored(task);
                requeued++;
            }
        }
        if (requeued > 0) {
            logger.info("Rehydrated {} task(s) into the scheduler", requeued);
        }
    }

    /**
     * Sets the policy used to compute the next crawl time for completed tasks.
     * When null (default), completed tasks are not automatically recrawled.
     */
    public void setRecrawlPolicy(java.util.function.Function<URI, Instant> recrawlPolicy) {
        this.recrawlPolicy = recrawlPolicy;
    }

    public void restoreWorkerHeartbeat(WorkerHeartbeat heartbeat) {
        workerHeartbeats.put(heartbeat.getWorkerId(), heartbeat);
    }

    public CrawlTask getTask(String urlString) {
        return taskRegistry.get(urlString);
    }

    public int getRegistrySize() {
        return taskRegistry.size();
    }

    public Map<String, Object> getStats() {
        return Map.of(
            "totalEnqueued", totalEnqueued.sum(),
            "totalDuplicates", totalDuplicates.sum(),
            "totalAssigned", totalAssigned.sum(),
            "totalCompleted", totalCompleted.sum(),
            "totalFailed", totalFailed.sum(),
            "pendingTasks", scheduler.getQueueSize(),
            "registeredTasks", taskRegistry.size(),
            "activeWorkers", workerHeartbeats.size(),
            "bloomFilterBits", bloomFilter.getBitCount()
        );
    }
}
