package com.minigoogle.distributed.sharding;

/**
 * Determines which shard owns a given term or document
 * using simple modulo arithmetic over the term's hash code.
 */
public class HashSharder {

    private final int numShards;

    public HashSharder(int numShards) {
        if (numShards <= 0) {
            throw new IllegalArgumentException("Number of shards must be > 0");
        }
        this.numShards = numShards;
    }

    /**
     * Returns the shard ID [0, numShards - 1] for a given term.
     */
    public int getShardId(String term) {
        if (term == null) return 0;
        // Use Math.abs because hashCode can be negative.
        // Math.abs(Integer.MIN_VALUE) is still negative, so we do bitwise AND with Integer.MAX_VALUE
        return (term.hashCode() & Integer.MAX_VALUE) % numShards;
    }

    public int getNumShards() {
        return numShards;
    }
}
