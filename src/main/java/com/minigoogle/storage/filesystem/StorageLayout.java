package com.minigoogle.storage.filesystem;

import java.nio.file.Path;

/**
 * Defines the physical directory structure for shards and segments.
 */
public class StorageLayout {

    private final Path baseDirectory;

    public StorageLayout(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    /**
     * @return The base directory for all shards on this node.
     */
    public Path getBaseDirectory() {
        return baseDirectory;
    }

    /**
     * @param shardId The shard ID.
     * @return The directory path for a specific shard.
     */
    public Path getShardDirectory(int shardId) {
        return baseDirectory.resolve("shard-" + shardId);
    }

    /**
     * @param shardId   The shard ID.
     * @param segmentId The segment ID.
     * @return The directory path for a specific segment within a shard.
     */
    public Path getSegmentDirectory(int shardId, String segmentId) {
        return getShardDirectory(shardId).resolve("segment-" + segmentId);
    }
}
