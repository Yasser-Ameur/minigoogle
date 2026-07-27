package com.minigoogle.distributed.model;

import java.util.List;

/**
 * Record representing a shard with its ID, primary owning node, and list of replica node IDs.
 */
public record ShardInfo(
        int shardId,
        String primaryNodeId,
        List<String> replicaNodeIds
) {
}
