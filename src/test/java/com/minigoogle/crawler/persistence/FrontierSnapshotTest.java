package com.minigoogle.crawler.persistence;

import com.minigoogle.crawler.bloom.BloomFilter;
import com.minigoogle.crawler.frontier.DistributedFrontier;
import com.minigoogle.crawler.heartbeat.WorkerHeartbeat;
import com.minigoogle.crawler.model.CrawlTask;
import com.minigoogle.crawler.model.UrlState;
import com.minigoogle.crawler.robots.RobotsCache;
import com.minigoogle.crawler.robots.RobotsManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for FrontierSnapshot persistence functionality. */
class FrontierSnapshotTest {

    @TempDir
    java.nio.file.Path tempDir;

    private FrontierSnapshot snapshot;

    @BeforeEach
    void setUp() {
        snapshot = new FrontierSnapshot(tempDir.toString());
    }

    @Test
    void testSaveAndLoadBloomFilter() throws IOException {
        BloomFilter original = new BloomFilter(1000, 0.01);
        original.add("https://example.com");
        original.add("https://google.com");
        original.add("https://github.com");

        String bloomPath = tempDir.resolve("bloom_test.bin").toString();
        snapshot.saveBloomFilter(original, bloomPath);

        BloomFilter loaded = FrontierSnapshot.loadBloomFilter(bloomPath);

        assertTrue(loaded.probablyContains("https://example.com"));
        assertTrue(loaded.probablyContains("https://google.com"));
        assertTrue(loaded.probablyContains("https://github.com"));
        assertFalse(loaded.probablyContains("https://nonexistent.com"));
    }

    @Test
    void testSaveCreatesDirectory() throws IOException {
        java.nio.file.Path newDir = tempDir.resolve("new_snapshots");
        FrontierSnapshot newSnapshot = new FrontierSnapshot(newDir.toString());

        BloomFilter filter = new BloomFilter(100, 0.01);
        filter.add("https://test.com");

        String bloomPath = newDir.resolve("bloom.bin").toString();
        newSnapshot.saveBloomFilter(filter, bloomPath);

        assertTrue(newDir.toFile().exists());
    }

    @Test
    void testMultipleSaveAndLoad() throws IOException {
        BloomFilter filter1 = new BloomFilter(1000, 0.01);
        filter1.add("https://first.com");

        BloomFilter filter2 = new BloomFilter(1000, 0.01);
        filter2.add("https://second.com");

        String path1 = tempDir.resolve("bloom1.bin").toString();
        String path2 = tempDir.resolve("bloom2.bin").toString();

        snapshot.saveBloomFilter(filter1, path1);
        snapshot.saveBloomFilter(filter2, path2);

        BloomFilter loaded1 = FrontierSnapshot.loadBloomFilter(path1);
        BloomFilter loaded2 = FrontierSnapshot.loadBloomFilter(path2);

        assertTrue(loaded1.probablyContains("https://first.com"));
        assertFalse(loaded1.probablyContains("https://second.com"));
        assertTrue(loaded2.probablyContains("https://second.com"));
        assertFalse(loaded2.probablyContains("https://first.com"));
    }

    @Test
    void testSaveAndRestoreFullSnapshot() throws IOException {
        DistributedFrontier frontier = new DistributedFrontier(10000, 0.01, 5000);

        frontier.addUrl(URI.create("https://example.com/page1"), 0);
        frontier.addUrl(URI.create("https://google.com/search"), 1);
        frontier.addUrl(URI.create("https://github.com/repo"), 2);

        CrawlTask task = frontier.requestWork("worker-0").orElseThrow();

        WorkerHeartbeat hb = new WorkerHeartbeat("worker-0", Duration.ofSeconds(10));
        hb.startTask(task);
        frontier.registerWorkerHeartbeat(hb);

        RobotsCache robotsCache = new RobotsCache(new RobotsManager());

        snapshot.save(frontier, robotsCache);

        DistributedFrontier newFrontier = new DistributedFrontier(10000, 0.01, 5000);
        FrontierSnapshot.SnapshotResult result = FrontierSnapshot.restore(newFrontier, tempDir.toString());

        assertTrue(result.restored());
        assertEquals(3, newFrontier.getRegistrySize());
        assertNotNull(newFrontier.getTask("https://example.com/page1"));
        assertNotNull(newFrontier.getTask("https://google.com/search"));
        assertNotNull(newFrontier.getTask("https://github.com/repo"));

        boolean foundAssigned = newFrontier.getTaskRegistry().values().stream()
            .anyMatch(t -> t.getState() == UrlState.ASSIGNED);
        assertTrue(foundAssigned, "At least one task should be in ASSIGNED state after restore");

        boolean foundQueued = newFrontier.getTaskRegistry().values().stream()
            .anyMatch(t -> t.getState() == UrlState.QUEUED);
        assertTrue(foundQueued, "Other tasks should be in QUEUED state after restore");

        CrawlTask restoredTask = newFrontier.getTask("https://example.com/page1");
        assertEquals(0, restoredTask.getDepth());

        assertEquals(1, newFrontier.getWorkerHeartbeats().size());
        assertNotNull(newFrontier.getWorkerHeartbeats().get("worker-0"));
    }

    @Test
    void testRestoreFromEmptyDirectory() throws IOException {
        java.nio.file.Path emptyDir = tempDir.resolve("empty");

        DistributedFrontier frontier = new DistributedFrontier(10000, 0.01, 5000);
        FrontierSnapshot.SnapshotResult result = FrontierSnapshot.restore(frontier, emptyDir.toString());

        assertFalse(result.restored());
        assertEquals(0, frontier.getRegistrySize());
    }

    @Test
    void testSaveAndRestoreWithRetryTasks() throws IOException {
        DistributedFrontier frontier = new DistributedFrontier(10000, 0.01, 5000);

        CrawlTask task = new CrawlTask(URI.create("https://example.com"), "example.com", 3, Instant.now());
        task.setState(UrlState.RETRY);
        task.setPriority(75);
        task.setNextAllowedFetch(Instant.now().plus(Duration.ofMinutes(5)));
        task.setNextCrawl(Instant.now().plus(Duration.ofHours(1)));
        task.setAssignedWorkerId("worker-0");
        task.incrementRetryCount();
        task.incrementRetryCount();

        frontier.restoreTask(task);

        snapshot.save(frontier, null);

        DistributedFrontier newFrontier = new DistributedFrontier(10000, 0.01, 5000);
        FrontierSnapshot.SnapshotResult result = FrontierSnapshot.restore(newFrontier, tempDir.toString());

        assertTrue(result.restored());
        CrawlTask restored = newFrontier.getTask("https://example.com");
        assertNotNull(restored);
        assertEquals(UrlState.RETRY, restored.getState());
        assertEquals(75, restored.getPriority());
        assertEquals(3, restored.getDepth());
        assertEquals("worker-0", restored.getAssignedWorkerId());
        assertEquals(2, restored.getRetryCount());
        assertNotNull(restored.getNextCrawl());
    }
}
