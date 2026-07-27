package com.minigoogle.distributed.sharding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for HashSharder functionality. */
class HashSharderTest {

    @Test
    void testShardDistribution() {
        HashSharder sharder = new HashSharder(4);

        int shard1 = sharder.getShardId("java");
        int shard2 = sharder.getShardId("python");
        
        assertTrue(shard1 >= 0 && shard1 < 4, "Shard ID should be within bounds");
        assertTrue(shard2 >= 0 && shard2 < 4, "Shard ID should be within bounds");
    }

    @Test
    void testNegativeHashCodes() {
        HashSharder sharder = new HashSharder(3);
        
        // Construct a string that has a negative hashCode
        String negativeHashStr = "polygenelubricants";
        assertTrue(negativeHashStr.hashCode() < 0);
        
        int shard = sharder.getShardId(negativeHashStr);
        assertTrue(shard >= 0 && shard < 3, "Shard ID must be non-negative even if hashCode is negative");
    }
}
