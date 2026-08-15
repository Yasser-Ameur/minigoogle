package com.minigoogle.indexer;

import com.minigoogle.crawler.model.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Production index sink for the crawl pipeline: collects every parsed document
 * the crawler produces and persists them into a real on-disk index when the
 * crawl session ends. This is what turns the crawler from a log-only exercise
 * into a crawl-to-index pipeline.
 *
 * <p>The sink is safe to call from a single coordinator indexer-consumer thread
 * (the contract of {@code CrawlCoordinator#addIndexSink}); {@link #accept} and
 * {@link #close} synchronize on the shared builder so concurrent callers are
 * tolerated as well.</p>
 */
public final class CrawlIndexSink implements Consumer<ParsedDocument>, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(CrawlIndexSink.class);

    private final IndexBuilder builder = new IndexBuilder();
    private final Path indexDir;
    private final Object lock = new Object();
    private boolean closed;

    public CrawlIndexSink(Path indexDir) {
        this.indexDir = indexDir;
    }

    @Override
    public void accept(ParsedDocument doc) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("CrawlIndexSink is already closed");
            }
            builder.processDocument(doc);
        }
    }

    public int getProcessedDocumentCount() {
        synchronized (lock) {
            return builder.getProcessedDocuments().size();
        }
    }

    /**
     * Flushes every collected document to the configured index directory and
     * marks the sink closed. Runs exactly once; subsequent calls are no-ops.
     * Failures are logged but never thrown, so a failed final flush cannot
     * abort the crawler's shutdown sequence.
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        try {
            Files.createDirectories(indexDir);
            builder.flush(
                    indexDir.resolve("dictionary.bin").toString(),
                    indexDir.resolve("postings.bin").toString(),
                    indexDir.resolve("documents.bin").toString());
            logger.info("Indexed {} crawled pages into {}", builder.getProcessedDocuments().size(), indexDir);
        } catch (IOException e) {
            logger.error("Failed to flush crawl index into {}", indexDir, e);
        }
    }
}
