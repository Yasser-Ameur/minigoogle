package com.minigoogle.storage.shard;

import java.util.List;
import java.util.UUID;

/**
 * Metadata for a single shard replica.
 *
 * @param shardId       The logical ID of the shard.
 * @param leader        The NodeId of the leader node.
 * @param replicas      The NodeIds of all follower replicas.
 * @param documentCount The total number of documents in this shard replica.
 * @param sizeInBytes   The physical size of this shard replica on disk.
 * @param version       The metadata version (increments on segment changes).
 */
public record ShardMetadata(
        int shardId,
        String leader,
        List<String> replicas,
        long documentCount,
        long sizeInBytes,
        long version
) {
    public ShardMetadata withVersion(long newVersion) {
        return new ShardMetadata(shardId, leader, replicas, documentCount, sizeInBytes, newVersion);
    }
}
