package com.minigoogle.storage.shard;

import com.minigoogle.storage.segment.Segment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a logical partition of the index on this node.
 * Contains multiple immutable segments.
 */
public class Shard {

    private final ShardMetadata metadata;
    private final Path shardDirectory;
    private final List<Segment> segments;

    public Shard(ShardMetadata metadata, Path shardDirectory, List<Segment> initialSegments) {
        this.metadata = metadata;
        this.shardDirectory = shardDirectory;
        this.segments = new ArrayList<>(initialSegments != null ? initialSegments : List.of());
    }

    public ShardMetadata getMetadata() {
        return metadata;
    }

    public Path getShardDirectory() {
        return shardDirectory;
    }

    public List<Segment> getSegments() {
        return Collections.unmodifiableList(segments);
    }

    /**
     * Adds a newly created, immutable segment to this shard.
     * This is thread-safe as it just adds to the list and updates metadata in a lock if needed.
     * For simplicity, using synchronized block.
     */
    public synchronized void addSegment(Segment segment) {
        segments.add(segment);
    }

    /**
     * Replaces old merged segments with a new compacted segment.
     */
    public synchronized void replaceSegments(List<Segment> oldSegments, Segment newSegment) {
        segments.removeAll(oldSegments);
        segments.add(newSegment);
    }
}
