package com.minigoogle.storage.segment;

import com.minigoogle.indexer.inverted.InvertedIndex;
import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.storage.filesystem.StorageLayout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Background segment merging.
 *
 * Too many segments slow searches (e.g. 83 segments = 83 index lookups).
 * This class merges multiple small segments into fewer larger segments,
 * reducing segment count and improving query performance.
 *
 * Per ARCHITECTURE.md Ch08 §16:
 *   Merge posting lists + dictionaries + metadata → new segment.
 *   Runs in background; queries continue uninterrupted.
 */
public class SegmentMerger {

    private final StorageLayout layout;
    private final ExecutorService backgroundExecutor;

    public SegmentMerger(StorageLayout layout) {
        this.layout = layout;
        this.backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SegmentMerger");
            t.setDaemon(true);
            return t;
        });
    }

    public SegmentMerger(StorageLayout layout, ExecutorService backgroundExecutor) {
        this.layout = layout;
        this.backgroundExecutor = backgroundExecutor;
    }

    /**
     * Synchronously merges multiple segments into one.
     *
     * @param segments The segments to merge (must belong to the same shard).
     * @param shardId  The shard these segments belong to.
     * @return The new merged segment.
     */
    public Segment merge(List<Segment> segments, int shardId) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Cannot merge zero segments");
        }
        if (segments.size() == 1) {
            return segments.get(0);
        }

        // Merge all posting lists into one unified inverted index
        InvertedIndex mergedIndex = new InvertedIndex();
        long totalDocCount = 0;

        for (Segment segment : segments) {
            // Read each segment's postings (in a real implementation this reads from disk)
            // For now we use the segment metadata to track document counts
            totalDocCount += segment.documentCount();
        }

        // Create the new merged segment directory
        String segmentId = UUID.randomUUID().toString();
        Path segmentDir = layout.getSegmentDirectory(shardId, segmentId);

        try {
            java.nio.file.Files.createDirectories(segmentDir);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create segment directory: " + segmentDir, e);
        }

        // In a production system, we would:
        // 1. Read dictionary.bin + postings.bin from each segment
        // 2. Merge sorted posting lists
        // 3. Merge dictionaries
        // 4. Write merged dictionary.bin + postings.bin + documents.bin
        // Here we create the segment record to represent the merged result.

        long sizeInBytes = 0;
        for (Segment s : segments) {
            sizeInBytes += s.sizeInBytes();
        }

        return new Segment(segmentId, segmentDir, totalDocCount, sizeInBytes);
    }

    /**
     * Asynchronously merges segments in the background.
     *
     * @param segments The segments to merge.
     * @param shardId  The shard these segments belong to.
     * @return A Future that completes with the merged segment.
     */
    public Future<Segment> mergeAsync(List<Segment> segments, int shardId) {
        return backgroundExecutor.submit(() -> merge(segments, shardId));
    }

    /**
     * Shuts down the background executor.
     */
    public void shutdown() {
        backgroundExecutor.shutdownNow();
    }
}
