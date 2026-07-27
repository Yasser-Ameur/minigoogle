package com.minigoogle.storage.segment;

import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.indexer.IndexBuilder;
import com.minigoogle.storage.filesystem.StorageLayout;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Creates a new, immutable index segment.
 */
public class SegmentWriter {

    private final StorageLayout layout;

    public SegmentWriter(StorageLayout layout) {
        this.layout = layout;
    }

    /**
     * Builds a new segment for the given shard containing the provided documents.
     */
    public Segment writeSegment(int shardId, Iterable<ParsedDocument> documents) throws Exception {
        String segmentId = UUID.randomUUID().toString();
        Path segmentDir = layout.getSegmentDirectory(shardId, segmentId);
        
        // Ensure directory exists
        java.nio.file.Files.createDirectories(segmentDir);

        IndexBuilder builder = new IndexBuilder();
        long docCount = 0;
        for (ParsedDocument doc : documents) {
            builder.processDocument(doc);
            docCount++;
        }
        
        // Finalize the segment files
        builder.flush(
                segmentDir.resolve("dictionary.bin").toString(),
                segmentDir.resolve("postings.bin").toString(),
                segmentDir.resolve("documents.bin").toString()
        );

        long sizeInBytes = 0; // Calculate actual size if needed
        return new Segment(segmentId, segmentDir, docCount, sizeInBytes);
    }
}
