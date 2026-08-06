package com.minigoogle.crawler.frontier;

import com.minigoogle.crawler.heartbeat.WorkerHeartbeat;
import com.minigoogle.crawler.model.CrawlTask;
import com.minigoogle.crawler.model.UrlState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for DistributedFrontier functionality. */
class DistributedFrontierTest {

    private DistributedFrontier frontier;

    @BeforeEach
    void setUp() {
        frontier = new DistributedFrontier(10000, 0.01, 5000);
    }

    @Test
    void testAddUrl() {
        boolean added = frontier.addUrl(URI.create("https://example.com"), 0);
        assertTrue(added);
        assertEquals(1, frontier.getRegistrySize());
    }

    @Test
    void testDuplicateRejection() {
        boolean first = frontier.addUrl(URI.create("https://example.com"), 0);
        boolean second = frontier.addUrl(URI.create("https://example.com"), 0);

        assertTrue(first);
        assertFalse(second);
        assertEquals(1, frontier.getRegistrySize());
    }

    @Test
    void testIsDuplicate() {
        assertFalse(frontier.isDuplicate(URI.create("https://example.com")));
        frontier.addUrl(URI.create("https://example.com"), 0);
        assertTrue(frontier.isDuplicate(URI.create("https://example.com")));
    }

    @Test
    void testRequestWork() {
        frontier.addUrl(URI.create("https://example.com/page1"), 0);
        frontier.addUrl(URI.create("https://google.com/page1"), 0);

        Optional<CrawlTask> task1 = frontier.requestWork("worker-1");
        assertTrue(task1.isPresent());
        assertEquals(UrlState.ASSIGNED, task1.get().getState());
        assertEquals("worker-1", task1.get().getAssignedWorkerId());

        Optional<CrawlTask> task2 = frontier.requestWork("worker-1");
        assertTrue(task2.isPresent());
        assertNotEquals(task1.get().getUrl(), task2.get().getUrl());
    }

    @Test
    void testRequestWorkEmpty() {
        Optional<CrawlTask> task = frontier.requestWork("worker-1");
        assertFalse(task.isPresent());
    }

    @Test
    void testCompleteTask() {
        frontier.addUrl(URI.create("https://example.com"), 0);
        CrawlTask task = frontier.requestWork("worker-1").orElseThrow();

        frontier.completeTask(task.getUrl().toString());
        assertEquals(UrlState.INDEXED, task.getState());

        Map<String, Object> stats = frontier.getStats();
        assertEquals(1L, stats.get("totalCompleted"));
    }

    @Test
    void testFailTask() {
        frontier.addUrl(URI.create("https://example.com"), 0);
        CrawlTask task = frontier.requestWork("worker-1").orElseThrow();

        frontier.failTask(task.getUrl().toString(), "worker-1");
        assertEquals(1L, frontier.getStats().get("totalFailed"));
    }

    @Test
    void testWorkerHeartbeatRegistration() {
        WorkerHeartbeat heartbeat = new WorkerHeartbeat("worker-1", Duration.ofSeconds(10));
        frontier.registerWorkerHeartbeat(heartbeat);

        Map<String, Object> stats = frontier.getStats();
        assertEquals(1, stats.get("activeWorkers"));
    }

    @Test
    void testRecoverFailedWorkerTasks() {
        frontier.addUrl(URI.create("https://example.com"), 0);
        CrawlTask task = frontier.requestWork("worker-1").orElseThrow();

        List<CrawlTask> recovered = frontier.recoverFailedWorkerTasks("worker-1");
        assertFalse(recovered.isEmpty());

        boolean found = recovered.stream()
            .anyMatch(t -> t.getUrl().toString().equals("https://example.com"));
        assertTrue(found);
        assertEquals(UrlState.QUEUED, task.getState());
    }

    @Test
    void testWorkerHealthCheck() {
        WorkerHeartbeat heartbeat = new WorkerHeartbeat("worker-1", Duration.ofMillis(1));
        frontier.registerWorkerHeartbeat(heartbeat);

        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<String> failed = frontier.checkWorkerHealth();
        assertEquals(1, failed.size());
        assertEquals("worker-1", failed.get(0));
    }

    @Test
    void testGetStats() {
        frontier.addUrl(URI.create("https://example.com"), 0);
        Map<String, Object> stats = frontier.getStats();

        assertEquals(1L, stats.get("totalEnqueued"));
        assertEquals(0L, stats.get("totalDuplicates"));
        assertEquals(1, stats.get("registeredTasks"));
        assertTrue((int) stats.get("bloomFilterBits") > 0);
    }

    @Test
    void testMultipleDomains() {
        frontier.addUrl(URI.create("https://google.com"), 0);
        frontier.addUrl(URI.create("https://github.com"), 0);
        frontier.addUrl(URI.create("https://example.com"), 0);

        assertEquals(3, frontier.getRegistrySize());

        Optional<CrawlTask> task1 = frontier.requestWork("worker-1");
        Optional<CrawlTask> task2 = frontier.requestWork("worker-1");

        assertTrue(task1.isPresent());
        assertTrue(task2.isPresent());
        assertNotEquals(task1.get().getDomain(), task2.get().getDomain());
    }

    @Test
    void testRehydrateSchedulerRestoresQueuedTasks() {
        CrawlTask task1 = new CrawlTask(URI.create("https://example.com"), "example.com", 0, Instant.now());
        task1.setState(UrlState.QUEUED);
        CrawlTask task2 = new CrawlTask(URI.create("https://google.com"), "google.com", 0, Instant.now());
        task2.setState(UrlState.QUEUED);

        frontier.restoreTask(task1);
        frontier.restoreTask(task2);

        assertEquals(0, frontier.getScheduler().getQueueSize(), "Restored tasks are not in the scheduler yet");

        frontier.rehydrateScheduler();

        assertEquals(2, frontier.getScheduler().getQueueSize());
        Optional<CrawlTask> dispatched = frontier.requestWork("worker-1");
        assertTrue(dispatched.isPresent());
        assertEquals(UrlState.ASSIGNED, dispatched.get().getState());
    }

    @Test
    void testRehydratedRetryTaskCanBeDispatched() {
        CrawlTask task = new CrawlTask(URI.create("https://example.com"), "example.com", 0, Instant.now());
        task.setState(UrlState.RETRY);

        frontier.restoreTask(task);
        frontier.rehydrateScheduler();

        Optional<CrawlTask> dispatched = frontier.requestWork("worker-1");
        assertTrue(dispatched.isPresent(), "Restored retry task should be dispatchable");
        assertEquals(UrlState.ASSIGNED, dispatched.get().getState());
    }

    @Test
    void testCompleteTaskReschedulesNextCrawl() throws InterruptedException {
        frontier.setRecrawlPolicy(uri -> Instant.now().minus(Duration.ofHours(1)));
        frontier.addUrl(URI.create("https://example.com"), 0);
        frontier.getScheduler().updateCrawlDelay("example.com", 50);

        CrawlTask task = frontier.requestWork("worker-1").orElseThrow();
        frontier.completeTask(task.getUrl().toString());

        assertEquals(UrlState.INDEXED, task.getState());

        Thread.sleep(80);
        Optional<CrawlTask> recrawled = frontier.requestWork("worker-1");
        assertTrue(recrawled.isPresent(), "Due recrawl task should be re-dispatched");
        assertEquals("https://example.com", recrawled.get().getUrl().toString());
        assertEquals(UrlState.ASSIGNED, recrawled.get().getState());
    }

    @Test
    void testNoRecrawlBeforeNextCrawlDue() {
        frontier.setRecrawlPolicy(uri -> Instant.now().plus(Duration.ofHours(24)));

        frontier.addUrl(URI.create("https://example.com"), 0);
        CrawlTask task = frontier.requestWork("worker-1").orElseThrow();
        frontier.completeTask(task.getUrl().toString());

        assertTrue(frontier.requestWork("worker-1").isEmpty(), "Not-yet-due tasks should not be recrawled");
    }
}
