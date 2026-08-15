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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

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
    void testFreshHeartbeatNotFlaggedStale() {
        WorkerHeartbeat heartbeat = new WorkerHeartbeat("worker-1", Duration.ofMillis(200));
        frontier.registerWorkerHeartbeat(heartbeat);
        // The worker ticks its own heartbeat while idle/running.
        heartbeat.tick();

        List<String> failed = frontier.checkWorkerHealth();
        assertTrue(failed.isEmpty(), "A freshly heartbeated worker must not be flagged stale");
    }

    @Test
    void testCompleteTaskUpdatesWorkerHeartbeat() {
        WorkerHeartbeat heartbeat = new WorkerHeartbeat("worker-1", Duration.ofSeconds(10));
        frontier.registerWorkerHeartbeat(heartbeat);

        frontier.addUrl(URI.create("https://example.com"), 0);
        frontier.requestWork("worker-1");
        assertNotNull(heartbeat.getCurrentTask(), "Assigned task should be tracked on the heartbeat");

        frontier.completeTask("https://example.com");
        assertNull(heartbeat.getCurrentTask());
        assertEquals(1, heartbeat.getTotalTasksCompleted());
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

    @Test
    void testConcurrentDuplicateEnqueueEnqueuesOnlyOnce() throws InterruptedException {
        int threads = 16;
        URI url = URI.create("https://example.com/same-url");
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (frontier.addUrl(url, 1)) {
                    accepted.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "Enqueue threads did not finish");

        assertEquals(1, accepted.get(), "Exactly one concurrent enqueue should win the dedup race");
        assertEquals(1, frontier.getRegistrySize());
        assertEquals(1L, frontier.getStats().get("totalEnqueued"));
        assertEquals(threads - 1L, frontier.getStats().get("totalDuplicates"));

        Optional<CrawlTask> dispatched = frontier.requestWork("worker-1");
        assertTrue(dispatched.isPresent());
        Optional<CrawlTask> second = frontier.requestWork("worker-1");
        assertTrue(second.isEmpty(), "The URL must not be dispatched twice from the scheduler");
    }

    @Test
    void testRegistryEvictedDownToLimit() {
        DistributedFrontier small = new DistributedFrontier(10000, 0.01, 5000, 5);

        for (int i = 0; i < 5; i++) {
            small.addUrl(URI.create("https://example.com/p" + i), 0);
        }
        small.completeTask("https://example.com/p0");
        small.completeTask("https://example.com/p1");
        small.completeTask("https://example.com/p2");

        // Two more URLs: the registry is at its limit of 5, so each enqueue
        // evicts one completed task. After both, all three completed tasks
        // (p0, p1, p2) have been reclaimed.
        small.addUrl(URI.create("https://example.com/p5"), 0);
        small.addUrl(URI.create("https://example.com/p6"), 0);

        assertEquals(5, small.getRegistrySize(),
                "Registry must be held at its limit while completed tasks remain to evict");

        // Active tasks are never eviction candidates, so all four must survive.
        assertNotNull(small.getTask("https://example.com/p3"), "Active queued tasks must be retained");
        assertNotNull(small.getTask("https://example.com/p4"));
        assertNotNull(small.getTask("https://example.com/p5"));
        assertNotNull(small.getTask("https://example.com/p6"));

        // Exactly two of the three completed tasks were reclaimed to make room.
        // Which two is not asserted: the eviction order sorts by discoveredAt,
        // and these tasks are enqueued far faster than the clock's resolution,
        // so ties are broken by registry iteration order.
        long completedRemaining = Stream.of("p0", "p1", "p2")
                .filter(p -> small.getTask("https://example.com/" + p) != null)
                .count();
        assertEquals(1, completedRemaining,
                "Completed tasks must be the ones evicted to honor the limit");
    }

    /**
     * Active tasks are never evicted, so once the active set alone exceeds the
     * configured limit the registry legitimately grows past it. This pins that
     * documented trade-off: bounding the registry must never cost us a queued
     * task, because dropping one would lose the URL entirely (the bloom filter
     * prevents it from ever being re-enqueued).
     */
    @Test
    void testRegistryGrowsPastLimitWhenAllTasksAreActive() {
        DistributedFrontier small = new DistributedFrontier(10000, 0.01, 5000, 5);

        for (int i = 0; i < 9; i++) {
            small.addUrl(URI.create("https://example.com/a" + i), 0);
        }

        assertEquals(9, small.getRegistrySize(),
                "Queued tasks must never be evicted to satisfy the registry limit");
        for (int i = 0; i < 9; i++) {
            assertNotNull(small.getTask("https://example.com/a" + i),
                    "active task a" + i + " must be retained");
        }
    }

    @Test
    void testActiveTasksNeverEvicted() {
        DistributedFrontier small = new DistributedFrontier(10000, 0.01, 5000, 3);
        for (int i = 0; i < 10; i++) {
            small.addUrl(URI.create("https://example.com/p" + i), 0);
        }

        assertEquals(10, small.getRegistrySize(), "Queued tasks cannot be evicted");
        for (int i = 0; i < 10; i++) {
            assertNotNull(small.getTask("https://example.com/p" + i));
        }
    }
}
