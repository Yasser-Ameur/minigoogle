package com.minigoogle.crawler.persistence;

import com.minigoogle.crawler.bloom.BloomFilter;
import com.minigoogle.crawler.frontier.DistributedFrontier;
import com.minigoogle.crawler.heartbeat.WorkerHeartbeat;
import com.minigoogle.crawler.model.CrawlTask;
import com.minigoogle.crawler.model.UrlState;
import com.minigoogle.crawler.robots.RobotsCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Serializes and deserializes frontier state for crash recovery.
 * Persists the bloom filter, task registry, worker heartbeats, and robots cache
 * to disk, and restores them on startup with automatic old-snapshot cleanup.
 */
public class FrontierSnapshot {

    private static final Logger logger = LoggerFactory.getLogger(FrontierSnapshot.class);
    private static final int SNAPSHOT_VERSION = 2;

    private final String snapshotDirectory;

    public FrontierSnapshot(String snapshotDirectory) {
        this.snapshotDirectory = snapshotDirectory;
    }

    public void save(DistributedFrontier frontier) throws IOException {
        save(frontier, null);
    }

    public void save(DistributedFrontier frontier, RobotsCache robotsCache) throws IOException {
        Path dir = Path.of(snapshotDirectory);
        Files.createDirectories(dir);

        String timestamp = Instant.now().toString().replace(":", "-");
        String bloomPath = dir.resolve("bloom_" + timestamp + ".bin").toString();
        String dataPath = dir.resolve("snapshot_" + timestamp + ".dat").toString();

        frontier.getBloomFilter().save(bloomPath);
        saveSnapshotData(frontier, robotsCache, dataPath);

        cleanupOldSnapshots(dir);
        logger.info("Frontier snapshot saved to {}", snapshotDirectory);
    }

    /**
     * Restores from the snapshot directory this instance was configured with.
     */
    public SnapshotResult restore(DistributedFrontier frontier) throws IOException {
        return restore(frontier, snapshotDirectory);
    }

    private void saveSnapshotData(DistributedFrontier frontier, RobotsCache robotsCache, String filePath) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(filePath)))) {
            dos.writeInt(SNAPSHOT_VERSION);

            Map<String, Object> stats = frontier.getStats();
            dos.writeLong(toLong(stats.get("totalEnqueued")));
            dos.writeLong(toLong(stats.get("totalDuplicates")));
            dos.writeLong(toLong(stats.get("totalAssigned")));
            dos.writeLong(toLong(stats.get("totalCompleted")));
            dos.writeLong(toLong(stats.get("totalFailed")));
            dos.writeLong(toLong(stats.get("bloomFilterBits")));

            Map<String, CrawlTask> tasks = frontier.getTaskRegistry();
            dos.writeInt(tasks.size());
            for (CrawlTask task : tasks.values()) {
                dos.writeUTF(task.getUrl().toString());
                dos.writeUTF(task.getDomain());
                dos.writeInt(task.getDepth());
                dos.writeLong(task.getDiscoveredAt().toEpochMilli());
                dos.writeUTF(task.getState().name());
                dos.writeInt(task.getPriority());
                dos.writeLong(task.getNextAllowedFetch().toEpochMilli());
                dos.writeLong(task.getNextCrawl() != null ? task.getNextCrawl().toEpochMilli() : -1L);
                String workerId = task.getAssignedWorkerId();
                dos.writeBoolean(workerId != null);
                if (workerId != null) dos.writeUTF(workerId);
                dos.writeInt(task.getRetryCount());
            }

            var workerHeartbeats = frontier.getWorkerHeartbeats();
            dos.writeInt(workerHeartbeats.size());
            for (WorkerHeartbeat hb : workerHeartbeats.values()) {
                dos.writeUTF(hb.getWorkerId());
                dos.writeLong(hb.getLastHeartbeat().toEpochMilli());
                dos.writeLong(hb.getTimeout().toMillis());
                dos.writeLong(hb.getTotalTasksCompleted());
                dos.writeLong(hb.getTotalTasksFailed());
            }

            boolean hasRobotsCache = robotsCache != null;
            dos.writeBoolean(hasRobotsCache);
            if (hasRobotsCache) {
                robotsCache.save(filePath + ".robots");
            }
        }
    }

    public static SnapshotResult restore(DistributedFrontier frontier, String snapshotDir) throws IOException {
        Path dir = Path.of(snapshotDir);

        File bloomFile = findLatestFile(dir, "bloom_", ".bin");
        File dataFile = findLatestFile(dir, "snapshot_", ".dat");

        if (bloomFile == null || dataFile == null) {
            logger.info("No snapshot found in {}", snapshotDir);
            return new SnapshotResult(false, null);
        }

        BloomFilter loadedBloom = BloomFilter.load(bloomFile.getAbsolutePath());
        mergeBloomFilter(frontier.getBloomFilter(), loadedBloom);

        RobotsCache loadedRobotsCache = null;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(dataFile)))) {
            int version = dis.readInt();
            if (version != SNAPSHOT_VERSION) {
                logger.warn("Incompatible snapshot version: {}, expected {}. Using what we can.", version, SNAPSHOT_VERSION);
            }

            dis.readLong(); // totalEnqueued
            dis.readLong(); // totalDuplicates
            dis.readLong(); // totalAssigned
            dis.readLong(); // totalCompleted
            dis.readLong(); // totalFailed
            dis.readLong(); // bloomFilterBits

            int taskCount = dis.readInt();
            for (int i = 0; i < taskCount; i++) {
                String urlStr = dis.readUTF();
                String domain = dis.readUTF();
                int depth = dis.readInt();
                Instant discoveredAt = Instant.ofEpochMilli(dis.readLong());
                UrlState state = UrlState.valueOf(dis.readUTF());
                int priority = dis.readInt();
                Instant nextAllowedFetch = Instant.ofEpochMilli(dis.readLong());
                long nextCrawlMillis = dis.readLong();
                Instant nextCrawl = nextCrawlMillis >= 0 ? Instant.ofEpochMilli(nextCrawlMillis) : null;
                String workerId = null;
                boolean hasWorkerId = dis.readBoolean();
                if (hasWorkerId) workerId = dis.readUTF();
                int retryCount = dis.readInt();

                java.net.URI uri = java.net.URI.create(urlStr);
                CrawlTask task = new CrawlTask(uri, domain, depth, discoveredAt);
                task.setState(state);
                task.setPriority(priority);
                task.setNextAllowedFetch(nextAllowedFetch);
                task.setNextCrawl(nextCrawl);
                task.setAssignedWorkerId(workerId);
                for (int r = 0; r < retryCount; r++) {
                    task.incrementRetryCount();
                }
                frontier.restoreTask(task);
            }

            int workerCount = dis.readInt();
            for (int i = 0; i < workerCount; i++) {
                String workerId = dis.readUTF();
                Instant lastHeartbeat = Instant.ofEpochMilli(dis.readLong());
                long timeoutMillis = dis.readLong();
                long completed = dis.readLong();
                long failed = dis.readLong();

                WorkerHeartbeat hb = new WorkerHeartbeat(workerId, Duration.ofMillis(timeoutMillis));
                for (int t = 0; t < completed; t++) hb.completeTask();
                for (int t = 0; t < failed; t++) hb.failTask();
                frontier.restoreWorkerHeartbeat(hb);
            }

            boolean hasRobotsCache = dis.readBoolean();
            if (hasRobotsCache) {
                String robotsPath = dataFile.getAbsolutePath() + ".robots";
                if (new File(robotsPath).exists()) {
                    loadedRobotsCache = RobotsCache.load(robotsPath);
                }
            }
        }

        logger.info("Snapshot restored: {} tasks, {} workers from {}", frontier.getRegistrySize(), frontier.getWorkerHeartbeats().size(), snapshotDir);
        return new SnapshotResult(true, loadedRobotsCache);
    }

    private static void mergeBloomFilter(BloomFilter target, BloomFilter source) {
        target.merge(source);
    }

    private static File findLatestFile(Path dir, String prefix, String suffix) {
        File[] files = dir.toFile().listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(suffix));
        if (files == null || files.length == 0) return null;
        File latest = files[0];
        for (File f : files) {
            if (f.lastModified() > latest.lastModified()) latest = f;
        }
        return latest;
    }

    private void cleanupOldSnapshots(Path dir) throws IOException {
        File[] bloomFiles = dir.toFile().listFiles((d, name) -> name.startsWith("bloom_") && name.endsWith(".bin"));
        File[] dataFiles = dir.toFile().listFiles((d, name) -> name.startsWith("snapshot_") && name.endsWith(".dat"));

        List<File> allSnapshots = new ArrayList<>();
        if (bloomFiles != null) allSnapshots.addAll(List.of(bloomFiles));
        if (dataFiles != null) allSnapshots.addAll(List.of(dataFiles));

        allSnapshots.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        while (allSnapshots.size() > 4) {
            File oldest = allSnapshots.remove(0);
            if (oldest.delete()) {
                logger.debug("Cleaned up old snapshot: {}", oldest.getName());
            }
        }
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        throw new IllegalArgumentException("Expected Number but got " + value.getClass());
    }

    public static BloomFilter loadBloomFilter(String filePath) throws IOException {
        return BloomFilter.load(filePath);
    }

    public void saveBloomFilter(BloomFilter filter, String filePath) throws IOException {
        Path parent = Path.of(filePath).getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        filter.save(filePath);
    }

    public record SnapshotResult(boolean restored, RobotsCache robotsCache) {}
}
