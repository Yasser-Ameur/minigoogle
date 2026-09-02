package com.minigoogle.cluster.placement;

import java.io.IOException;

/**
 * Accepts a document into this node's local index, whether it was crawled
 * here or delivered by a peer that owns it on the ring.
 *
 * <p>Implemented by the composition root over the node's own crawl store and
 * index. Idempotent by URL: ingesting a document whose URL is already
 * present is a no-op that returns {@code false}, so repeated placement or
 * repair delivery is always safe.
 */
public interface DocumentIngest {

    /**
     * @param doc The document to ingest.
     * @return {@code true} if the document was newly indexed, {@code false}
     *         if a document with the same URL was already present.
     */
    boolean ingest(IngestedDocument doc) throws IOException;
}
