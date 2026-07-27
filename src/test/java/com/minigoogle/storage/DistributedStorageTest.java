package com.minigoogle.storage;

import com.minigoogle.storage.filesystem.StorageLayout;
import com.minigoogle.storage.replication.ReplicaState;
import com.minigoogle.storage.replication.ReplicationManager;
import com.minigoogle.storage.segment.Segment;
import com.minigoogle.storage.shard.Shard;
import com.minigoogle.storage.shard.ShardManager;
import com.minigoogle.storage.shard.ShardMetadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for DistributedStorage (shards, segments, replication) functionality. */
class DistributedStorageTest {

    @Test
    void testStorageLayoutPaths() {
        StorageLayout layout = new StorageLayout(Path.of("/data"));
        assertEquals(Path.of("/data/shard-5"), layout.getShardDirectory(5));
        assertEquals(Path.of("/data/shard-5/segment-abc"), layout.getSegmentDirectory(5, "abc"));
    }

    @Test
    void testShardManager() {
        ShardManager manager = new ShardManager();
        ShardMetadata metadata = new ShardMetadata(1, "node1", List.of("node2"), 100, 1024, 1);
        Shard shard = new Shard(metadata, Path.of("/data/shard-1"), List.of());

        manager.addShard(shard);
        assertEquals(shard, manager.getShard(1));
        assertEquals(1, manager.getAllShards().size());

        manager.removeShard(1);
        assertNull(manager.getShard(1));
    }

    @Test
    void testReplicationManager() {
        ReplicationManager manager = new ReplicationManager();
        assertEquals(ReplicaState.UNASSIGNED, manager.getState(1));
        assertFalse(manager.isLeader(1));

        manager.setState(1, ReplicaState.LEADER);
        assertEquals(ReplicaState.LEADER, manager.getState(1));
        assertTrue(manager.isLeader(1));

        manager.setState(1, ReplicaState.FOLLOWER);
        assertEquals(ReplicaState.FOLLOWER, manager.getState(1));
        assertFalse(manager.isLeader(1));
    }

    @Test
    void testShardSegmentUpdates() {
        ShardMetadata metadata = new ShardMetadata(2, "node1", List.of(), 0, 0, 1);
        Shard shard = new Shard(metadata, Path.of("/data/shard-2"), List.of());

        Segment seg1 = new Segment("seg1", Path.of("/data/shard-2/segment-seg1"), 10, 500);
        shard.addSegment(seg1);

        assertEquals(1, shard.getSegments().size());
        assertEquals("seg1", shard.getSegments().get(0).segmentId());
    }
}
