package com.minigoogle.crawler.coordinator;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.core.config.ConfigurationLoader;
import com.minigoogle.crawler.downloader.Downloader;
import com.minigoogle.crawler.downloader.HttpDownloader;
import com.minigoogle.crawler.frontier.DistributedFrontier;
import com.minigoogle.crawler.heartbeat.WorkerHeartbeat;
import com.minigoogle.crawler.model.CrawlTask;
import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.crawler.normalization.StandardUrlNormalizer;
import com.minigoogle.crawler.parser.JSoupHtmlParser;
import com.minigoogle.crawler.persistence.FrontierSnapshot;
import com.minigoogle.crawler.robots.RobotsCache;
import com.minigoogle.crawler.robots.RobotsManager;
import com.minigoogle.crawler.worker.CrawlWorker;
import com.minigoogle.indexer.CrawlIndexSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Orchestrates the full crawl lifecycle: scheduling, downloading, parsing, and deduplication.
 * Manages worker threads, health monitoring, periodic snapshots, and robots.txt compliance
 * across the distributed crawl infrastructure.
 */
public class CrawlCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(CrawlCoordinator.class);

    private static final int BLOOM_FILTER_EXPECTED_ELEMENTS = 1_000_000;
    private static final double BLOOM_FILTER_FALSE_POSITIVE_RATE = 0.001;
    private static final long HEARTBEAT_TIMEOUT_MS = 15_000;
    private static final long HEALTH_CHECK_INTERVAL_MS = 5_000;
    private static final long SNAPSHOT_INTERVAL_MS = 300_000;
    private static final String SNAPSHOT_DIR = "snapshots/crawler";
    private static final int DEFAULT_MAX_DEPTH = 4;

    private static final Duration WIKI_RECRAWL_INTERVAL = Duration.ofMinutes(30);
    private static final Duration UNIVERSITY_RECRAWL_INTERVAL = Duration.ofDays(7);
    private static final Duration ARCHIVE_RECRAWL_INTERVAL = Duration.ofDays(365);
    private static final Duration DEFAULT_RECRAWL_INTERVAL = Duration.ofHours(24);

    private final DistributedFrontier frontier;
    private final StandardUrlNormalizer normalizer;
    private final RobotsCache robotsCache;
    private final FrontierSnapshot snapshot;
    private final Downloader downloader;
    private final ExecutorService workerPool;
    private final BlockingQueue<ParsedDocument> indexerQueue;
    private final List<Consumer<ParsedDocument>> indexSinks = new CopyOnWriteArrayList<>();
    private final int numWorkers;
    private final int maxDepth;
    private volatile boolean running;

    public CrawlCoordinator(int numWorkers) {
        this(numWorkers, DEFAULT_MAX_DEPTH, SNAPSHOT_DIR);
    }

    public CrawlCoordinator(int numWorkers, int maxDepth) {
        this(numWorkers, maxDepth, SNAPSHOT_DIR);
    }

    public CrawlCoordinator(int numWorkers, int maxDepth, String snapshotDir) {
        this(numWorkers, maxDepth, snapshotDir, new HttpDownloader());
    }

    public CrawlCoordinator(int numWorkers, int maxDepth, String snapshotDir, Downloader downloader) {
        this.numWorkers = numWorkers;
        this.maxDepth = maxDepth;
        this.downloader = downloader;
        this.frontier = new DistributedFrontier(BLOOM_FILTER_EXPECTED_ELEMENTS, BLOOM_FILTER_FALSE_POSITIVE_RATE, HEARTBEAT_TIMEOUT_MS);
        this.frontier.setRecrawlPolicy(this::nextRecrawlInstant);
        this.normalizer = new StandardUrlNormalizer();
        this.robotsCache = new RobotsCache(new RobotsManager());
        this.snapshot = new FrontierSnapshot(snapshotDir);
        this.indexerQueue = new LinkedBlockingQueue<>(10000);
        this.workerPool = Executors.newFixedThreadPool(numWorkers);
        this.running = false;
    }

    public void start(List<String> seedUrls) {
        logger.info("Starting distributed crawler with {} workers", numWorkers);
        running = true;

        restoreFromSnapshot();

        for (String seed : seedUrls) {
            enqueueUrl(seed, 0);
        }

        JSoupHtmlParser parser = new JSoupHtmlParser();
        for (int i = 0; i < numWorkers; i++) {
            String workerId = "worker-" + i;
            WorkerHeartbeat heartbeat = new WorkerHeartbeat(workerId, Duration.ofMillis(HEARTBEAT_TIMEOUT_MS));
            frontier.registerWorkerHeartbeat(heartbeat);

            CrawlWorker worker = new CrawlWorker(
                workerId,
                frontier,
                this.downloader,
                parser,
                indexerQueue,
                this::handleParsedDocument,
                robotsCache,
                heartbeat
            );
            workerPool.submit(worker);
        }

        startHealthChecker();
        startSnapshotScheduler();

        Thread indexerConsumer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ParsedDocument doc = indexerQueue.take();
                    logger.debug("Indexer queue received: {}", doc.url());
                    for (Consumer<ParsedDocument> sink : indexSinks) {
                        try {
                            sink.accept(doc);
                        } catch (Exception e) {
                            logger.error("Index sink failed for {}", doc.url(), e);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        indexerConsumer.setDaemon(true);
        indexerConsumer.start();
    }

    public void stop() {
        logger.info("Stopping distributed crawler...");
        running = false;
        workerPool.shutdownNow();

        try {
            if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            snapshot.save(frontier, robotsCache);
            logger.info("Final snapshot saved");
        } catch (IOException e) {
            logger.error("Failed to save final snapshot", e);
        }

        logStats();
    }

    private void restoreFromSnapshot() {
        try {
            FrontierSnapshot.SnapshotResult result = snapshot.restore(frontier);
            if (result.restored()) {
                frontier.rehydrateScheduler();
                logger.info("Restored from snapshot: {} tasks in registry", frontier.getRegistrySize());
                if (result.robotsCache() != null) {
                    logger.info("Restored robots cache with {} entries", result.robotsCache().cachedDomainCount());
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to restore from snapshot, starting fresh: {}", e.getMessage());
        }
    }

    private void handleParsedDocument(ParsedDocument doc) {
        CrawlTask parent = frontier.getTask(doc.url().toString());
        int parentDepth = parent != null ? parent.getDepth() : 0;
        for (URI link : doc.outgoingLinks()) {
            enqueueUrl(link.toString(), parentDepth + 1);
        }
    }

    private void enqueueUrl(String rawUrl, int depth) {
        Optional<URI> normalizedOpt = normalizer.normalize(rawUrl);

        if (normalizedOpt.isPresent()) {
            URI uri = normalizedOpt.get();

            if (depth > maxDepth) {
                logger.debug("Skipping URL beyond max depth {}: {}", maxDepth, uri);
                return;
            }

            if (frontier.isDuplicate(uri)) {
                return;
            }

            if (robotsCache.isAllowed(uri)) {
                frontier.addUrl(uri, depth);
                frontier.getScheduler().incrementLinkCount(uri.getHost().toLowerCase());

                long crawlDelay = robotsCache.getCrawlDelayMillis(uri);
                if (crawlDelay > 0) {
                    frontier.getScheduler().updateCrawlDelay(uri.getHost().toLowerCase(), crawlDelay);
                }

                CrawlTask task = frontier.getTask(uri.toString());
                if (task != null) {
                    task.setNextCrawl(computeNextCrawl(uri));
                }

                logger.debug("Enqueued new URL: {}", uri);
            } else {
                logger.debug("Disallowed by robots.txt: {}", uri);
            }
        }
    }

    private Instant nextRecrawlInstant(URI uri) {
        return Instant.now().plus(recrawlInterval(uri));
    }

    private Instant computeNextCrawl(URI uri) {
        return nextRecrawlInstant(uri);
    }

    private Duration recrawlInterval(URI uri) {
        String host = uri.getHost().toLowerCase();
        String path = uri.getPath() != null ? uri.getPath().toLowerCase() : "";

        if (host.contains("wikipedia.org") || host.contains("wikimedia.org")) {
            return WIKI_RECRAWL_INTERVAL;
        }

        if (host.endsWith(".edu") || host.endsWith(".ac.uk") || host.endsWith(".edu.au")) {
            return UNIVERSITY_RECRAWL_INTERVAL;
        }

        if (path.endsWith(".pdf") || path.endsWith(".doc") || path.endsWith(".docx") ||
            path.endsWith(".ps") || path.endsWith(".epub")) {
            return ARCHIVE_RECRAWL_INTERVAL;
        }

        if (path.endsWith("/wiki") || host.contains("wiki")) {
            return Duration.ofMinutes(60);
        }

        return DEFAULT_RECRAWL_INTERVAL;
    }

    private void startHealthChecker() {
        Thread healthChecker = new Thread(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(HEALTH_CHECK_INTERVAL_MS);

                    List<String> failedWorkers = frontier.checkWorkerHealth();
                    for (String workerId : failedWorkers) {
                        logger.warn("Worker {} detected as failed, recovering tasks", workerId);
                        List<CrawlTask> recoveredTasks = frontier.recoverFailedWorkerTasks(workerId);
                        logger.info("Recovered {} tasks from failed worker {}", recoveredTasks.size(), workerId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        healthChecker.setDaemon(true);
        healthChecker.setName("CrawlerHealthChecker");
        healthChecker.start();
    }

    private void startSnapshotScheduler() {
        Thread snapshotThread = new Thread(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(SNAPSHOT_INTERVAL_MS);
                    snapshot.save(frontier, robotsCache);
                    logger.debug("Periodic snapshot saved");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    logger.error("Failed to save periodic snapshot", e);
                }
            }
        });
        snapshotThread.setDaemon(true);
        snapshotThread.setName("CrawlerSnapshotScheduler");
        snapshotThread.start();
    }

    public DistributedFrontier getFrontier() {
        return frontier;
    }

    public RobotsCache getRobotsCache() {
        return robotsCache;
    }

    public BlockingQueue<ParsedDocument> getIndexerQueue() {
        return indexerQueue;
    }

    /**
     * Registers a sink that receives every parsed document produced by the
     * crawler, so downstream subsystems (e.g. the indexer) can consume crawled
     * pages as they arrive. Sinks run on the coordinator's indexer-consumer
     * thread and must be safe to call concurrently.
     */
    public void addIndexSink(Consumer<ParsedDocument> indexSink) {
        indexSinks.add(indexSink);
    }

    public boolean isRunning() {
        return running;
    }

    public void logStats() {
        logger.info("=== Crawl Coordinator Stats ===");
        frontier.getStats().forEach((key, value) ->
            logger.info("  {}: {}", key, value)
        );
        logger.info("================================");
    }

    /**
     * Standalone crawler entry point. Crawls the given seed URLs (or a default
     * demo seed) and persists every parsed page into a real search index via the
     * production {@link CrawlIndexSink}, so crawled content is searchable after
     * the run instead of being log-only.
     *
     * <p>Usage: {@code CrawlCoordinator [--config <path>] [--index-dir <dir>] [seedUrl...]}</p>
     *
     * @param args optional {@code --config} and {@code --index-dir} followed by seed URLs
     */
    public static void main(String[] args) {
        Path indexDir = Path.of("demo-index/crawled");
        String configPath = "config/application.yaml";
        List<String> seeds = new java.util.ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                configPath = args[++i];
            } else if ("--index-dir".equals(args[i]) && i + 1 < args.length) {
                indexDir = Path.of(args[++i]);
            } else if (args[i] != null && !args[i].isBlank()) {
                seeds.add(args[i]);
            }
        }

        if (seeds.isEmpty()) {
            seeds.add("https://quotes.toscrape.com");
        }

        Configuration config = ConfigurationLoader.load(configPath);
        int numWorkers = config.getInt("crawler.workers", 4);
        int maxDepth = config.getInt("crawler.maxDepth", DEFAULT_MAX_DEPTH);

        CrawlCoordinator coordinator = new CrawlCoordinator(numWorkers, maxDepth);
        CrawlIndexSink indexSink = new CrawlIndexSink(indexDir);
        coordinator.addIndexSink(indexSink);

        System.out.println("Crawling seeds " + seeds
            + " (workers=" + numWorkers + ", maxDepth=" + maxDepth
            + ") into index: " + indexDir);
        coordinator.start(seeds);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            coordinator.stop();
            indexSink.close();
        }));
    }
}
