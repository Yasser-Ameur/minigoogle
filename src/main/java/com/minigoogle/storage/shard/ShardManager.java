package com.minigoogle.storage.shard;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all the Shards assigned to this specific node.
 */
public class ShardManager {

    private final Map<Integer, Shard> shards = new ConcurrentHashMap<>();

    /**
     * Adds a newly assigned or recovered shard to this node.
     */
    public void addShard(Shard shard) {
        shards.put(shard.getMetadata().shardId(), shard);
    }

    /**
     * Retrieves a local shard by its ID.
     */
    public Shard getShard(int shardId) {
        return shards.get(shardId);
    }

    /**
     * Removes a shard from this node (e.g., during rebalancing).
     */
    public Shard removeShard(int shardId) {
        return shards.remove(shardId);
    }

    /**
     * Returns all shards hosted on this node.
     */
    public Map<Integer, Shard> getAllShards() {
        return Collections.unmodifiableMap(shards);
    }
}
