package com.minigoogle.distributed.registry;

import com.minigoogle.distributed.model.NodeInfo;
import com.minigoogle.distributed.model.ShardInfo;

import java.util.List;

/**
 * Record capturing the current cluster state with the list of nodes and shards.
 */
public record ClusterState(
        List<NodeInfo> nodes,
        List<ShardInfo> shards
) {
}
