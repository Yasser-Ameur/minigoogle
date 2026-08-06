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
import java.util.concurrent.locks.ReentrantLock;

/**
 * Distributed crawl frontier with bloom filter deduplication and worker coordination.
 * Manages the global URL task registry, dispatches work to workers via heartbeat-aware
 * assignment, and tracks enqueued, assigned, completed, and failed task statistics.
 */
public class DistributedFrontier {

    private static final Logger logger = LoggerFactory.getLogger(DistributedFrontier.class);

    private final BloomFilter bloomFilter;
    private final UrlScheduler scheduler;
    private final ConcurrentHashMap<String, CrawlTask> taskRegistry;
    private final ConcurrentHashMap<String, WorkerHeartbeat> workerHeartbeats;
    private final ReentrantLock assignmentLock;
    private final long heartbeatTimeoutMillis;

    private volatile long totalEnqueued;
    private volatile long totalDuplicates;
    private volatile long totalAssigned;
    private volatile long totalCompleted;
    private volatile long totalFailed;
    private volatile java.util.function.Function<URI, Instant> recrawlPolicy;

    public DistributedFrontier(int bloomFilterExpectedElements, double bloomFilterFalsePositiveRate, long heartbeatTimeoutMillis) {
        this.bloomFilter = new BloomFilter(bloomFilterExpectedElements, bloomFilterFalsePositiveRate);
        this.scheduler = new UrlScheduler();
        this.taskRegistry = new ConcurrentHashMap<>();
        this.workerHeartbeats = new ConcurrentHashMap<>();
        this.assignmentLock = new ReentrantLock();
        this.heartbeatTimeoutMillis = heartbeatTimeoutMillis;
        this.totalEnqueued = 0;
        this.totalDuplicates = 0;
        this.totalAssigned = 0;
        this.totalCompleted = 0;
        this.totalFailed = 0;
    }

    public boolean addUrl(URI url, int depth) {
        String urlString = url.toString();
        String domain = url.getHost().toLowerCase();

        if (bloomFilter.probablyContains(urlString)) {
            totalDuplicates++;
            logger.debug("Duplicate URL rejected: {}", url);
            return false;
        }

        bloomFilter.add(urlString);

        CrawlTask task = new CrawlTask(url, domain, depth, Instant.now());
        taskRegistry.put(urlString, task);
        scheduler.submitTask(task);
        totalEnqueued++;

        logger.debug("Enqueued URL: {} (total: {}, duplicates: {})", url, totalEnqueued, totalDuplicates);
        return true;
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
            totalAssigned++;

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
            totalCompleted++;
            scheduler.onTaskCompleted(urlString);
            logger.debug("Task completed: {}", urlString);
        }
    }

    public void failTask(String urlString, String workerId) {
        CrawlTask task = taskRegistry.get(urlString);
        if (task != null) {
            scheduler.onTaskFailed(task);
            totalFailed++;

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
            "totalEnqueued", totalEnqueued,
            "totalDuplicates", totalDuplicates,
            "totalAssigned", totalAssigned,
            "totalCompleted", totalCompleted,
            "totalFailed", totalFailed,
            "pendingTasks", scheduler.getQueueSize(),
            "registeredTasks", taskRegistry.size(),
            "activeWorkers", workerHeartbeats.size(),
            "bloomFilterBits", bloomFilter.getBitCount()
        );
    }
}
