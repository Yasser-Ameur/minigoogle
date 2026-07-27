package com.minigoogle.crawler.worker;

import com.minigoogle.crawler.downloader.Downloader;
import com.minigoogle.crawler.frontier.DistributedFrontier;
import com.minigoogle.crawler.model.CrawlTask;
import com.minigoogle.crawler.model.DownloadedPage;
import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.crawler.parser.HtmlParser;
import com.minigoogle.crawler.robots.RobotsCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Runnable worker executing the async download-parse-extract pipeline.
 * Maintains a configurable number of outstanding requests for concurrent
 * throughput while respecting frontier assignment and heartbeat reporting.
 */
public class CrawlWorker implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(CrawlWorker.class);
    private static final int MAX_OUTSTANDING_REQUESTS = 4;

    private final String workerId;
    private final DistributedFrontier frontier;
    private final Downloader downloader;
    private final HtmlParser parser;
    private final BlockingQueue<ParsedDocument> indexerQueue;
    private final Consumer<ParsedDocument> onDocumentParsed;
    private final RobotsCache robotsCache;
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> outstandingRequests;

    public CrawlWorker(String workerId, DistributedFrontier frontier, Downloader downloader,
                       HtmlParser parser, BlockingQueue<ParsedDocument> indexerQueue,
                       Consumer<ParsedDocument> onDocumentParsed, RobotsCache robotsCache) {
        this.workerId = workerId;
        this.frontier = frontier;
        this.downloader = downloader;
        this.parser = parser;
        this.indexerQueue = indexerQueue;
        this.onDocumentParsed = onDocumentParsed;
        this.robotsCache = robotsCache;
        this.outstandingRequests = new ConcurrentLinkedQueue<>();
    }

    @Override
    public void run() {
        logger.info("Worker {} started", workerId);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                outstandingRequests.removeIf(CompletableFuture::isDone);

                while (outstandingRequests.size() < MAX_OUTSTANDING_REQUESTS) {
                    Optional<CrawlTask> taskOpt = frontier.requestWork(workerId);
                    if (taskOpt.isEmpty()) break;

                    CrawlTask task = taskOpt.get();
                    CompletableFuture<Void> future = processTaskAsync(task);
                    outstandingRequests.add(future);
                }

                if (outstandingRequests.isEmpty()) {
                    Thread.sleep(100);
                } else {
                    CompletableFuture<?> any = outstandingRequests.peek();
                    if (any != null) {
                        any.get();
                    }
                }

            } catch (InterruptedException e) {
                logger.info("Worker {} thread interrupted, shutting down.", workerId);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Unexpected error in worker {} thread", workerId, e);
            }
        }

        waitForOutstandingRequests();
        logger.info("Worker {} stopped", workerId);
    }

    private CompletableFuture<Void> processTaskAsync(CrawlTask task) {
        return CompletableFuture.runAsync(() -> {
            try {
                logger.debug("Worker {} picked up task: {}", workerId, task.getUrl());

                task.markFetching();

                DownloadedPage page = downloader.download(new com.minigoogle.crawler.model.UrlTask(
                    task.getUrl().toString(),
                    task.getDomain(),
                    task.getDepth(),
                    task.getDiscoveredAt()
                ));

                if (page != null) {
                    Optional<ParsedDocument> parsedOpt = parser.parse(page);

                    if (parsedOpt.isPresent()) {
                        ParsedDocument doc = parsedOpt.get();
                        indexerQueue.put(doc);
                        onDocumentParsed.accept(doc);
                        frontier.completeTask(task.getUrl().toString());
                        logger.info("Worker {} successfully processed: {}", workerId, doc.url());
                    } else {
                        logger.warn("Worker {} failed to parse page: {}", workerId, task.getUrl());
                        frontier.failTask(task.getUrl().toString(), workerId);
                    }
                } else {
                    logger.warn("Worker {} failed to download: {}", workerId, task.getUrl());
                    frontier.failTask(task.getUrl().toString(), workerId);
                }
            } catch (Exception e) {
                logger.error("Error processing task {} in worker {}", task.getUrl(), workerId, e);
                frontier.failTask(task.getUrl().toString(), workerId);
            }
        });
    }

    private void waitForOutstandingRequests() {
        for (CompletableFuture<Void> future : outstandingRequests) {
            try {
                future.join();
            } catch (Exception e) {
                logger.debug("Error waiting for outstanding request completion: {}", e.getMessage());
            }
        }
        outstandingRequests.clear();
    }

    public String getWorkerId() {
        return workerId;
    }
}
