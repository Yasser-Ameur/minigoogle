package com.minigoogle.crawler.heartbeat;

import com.minigoogle.crawler.model.CrawlTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks worker liveness with configurable timeout detection.
 * Records the last heartbeat timestamp and current task assignment so the
 * coordinator can detect and recover from failed workers.
 */
public class WorkerHeartbeat {

    private static final Logger logger = LoggerFactory.getLogger(WorkerHeartbeat.class);

    private final String workerId;
    private final Duration timeout;
    private volatile Instant lastHeartbeat;
    private volatile CrawlTask currentTask;
    private volatile long totalTasksCompleted;
    private volatile long totalTasksFailed;

    public WorkerHeartbeat(Duration timeout) {
        this.workerId = UUID.randomUUID().toString().substring(0, 8);
        this.timeout = timeout;
        this.lastHeartbeat = Instant.now();
        this.currentTask = null;
        this.totalTasksCompleted = 0;
        this.totalTasksFailed = 0;
    }

    public WorkerHeartbeat(String workerId, Duration timeout) {
        this.workerId = workerId;
        this.timeout = timeout;
        this.lastHeartbeat = Instant.now();
        this.currentTask = null;
        this.totalTasksCompleted = 0;
        this.totalTasksFailed = 0;
    }

    public void tick() {
        this.lastHeartbeat = Instant.now();
    }

    public void startTask(CrawlTask task) {
        this.currentTask = task;
        tick();
    }

    public void completeTask() {
        this.currentTask = null;
        this.totalTasksCompleted++;
        tick();
    }

    public void failTask() {
        this.currentTask = null;
        this.totalTasksFailed++;
        tick();
    }

    public boolean isAlive() {
        return Duration.between(lastHeartbeat, Instant.now()).compareTo(timeout) < 0;
    }

    public Duration timeSinceLastHeartbeat() {
        return Duration.between(lastHeartbeat, Instant.now());
    }

    public Duration getTimeout() {
        return timeout;
    }

    public String getWorkerId() {
        return workerId;
    }

    public Instant getLastHeartbeat() {
        return lastHeartbeat;
    }

    public CrawlTask getCurrentTask() {
        return currentTask;
    }

    public long getTotalTasksCompleted() {
        return totalTasksCompleted;
    }

    public long getTotalTasksFailed() {
        return totalTasksFailed;
    }

    public Map<String, Object> getStatus() {
        return Map.of(
            "workerId", workerId,
            "alive", isAlive(),
            "lastHeartbeat", lastHeartbeat.toString(),
            "currentTask", currentTask != null ? currentTask.getUrl().toString() : "none",
            "completedTasks", totalTasksCompleted,
            "failedTasks", totalTasksFailed,
            "timeSinceLastHeartbeat", timeSinceLastHeartbeat().toMillis() + "ms"
        );
    }
}
