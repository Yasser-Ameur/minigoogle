package com.minigoogle.storage.compaction;

import com.minigoogle.storage.segment.Segment;
import com.minigoogle.storage.segment.SegmentMerger;
import com.minigoogle.storage.shard.Shard;
import com.minigoogle.storage.shard.ShardMetadata;
import com.minigoogle.storage.filesystem.StorageLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for CompactionManager functionality. */
class CompactionManagerTest {

    @TempDir
    Path tempDir;

    private SegmentMerger segmentMerger;
    private CompactionManager compactionManager;

    @BeforeEach
    void setUp() {
        StorageLayout layout = new StorageLayout(tempDir);
        segmentMerger = new SegmentMerger(layout);
        compactionManager = new CompactionManager(segmentMerger);
    }

    private Shard createShardWithSegments(int numSegments) {
        ShardMetadata metadata = new ShardMetadata(0, "test-shard", List.of(), 0, 0, 0);
        List<Segment> segments = new java.util.ArrayList<>();
        for (int i = 0; i < numSegments; i++) {
            Path segDir = tempDir.resolve("shard-0").resolve("seg-" + i);
            try { java.nio.file.Files.createDirectories(segDir); } catch (Exception e) { /* ok */ }
            segments.add(new Segment("seg-" + i, segDir, 100, 1024));
        }
        return new Shard(metadata, tempDir.resolve("shard-0"), segments);
    }

    @Test
    void testCompactSkipsEmptyDeletedSet() {
        Shard shard = createShardWithSegments(3);
        int segmentsBefore = shard.getSegments().size();
        compactionManager.compact(shard, Set.of());
        assertEquals(segmentsBefore, shard.getSegments().size());
    }

    @Test
    void testCompactSkipsNullDeletedSet() {
        Shard shard = createShardWithSegments(3);
        int segmentsBefore = shard.getSegments().size();
        compactionManager.compact(shard, null);
        assertEquals(segmentsBefore, shard.getSegments().size());
    }

    @Test
    void testCompactSkipsSingleSegment() {
        Shard shard = createShardWithSegments(1);
        compactionManager.compact(shard, Set.of(1));
        assertEquals(1, shard.getSegments().size());
    }

    @Test
    void testCompactMergesSegments() {
        Shard shard = createShardWithSegments(3);
        assertEquals(3, shard.getSegments().size());
        compactionManager.compact(shard, Set.of(1));
        assertEquals(1, shard.getSegments().size());
    }

    @Test
    void testCompactAsyncCompletes() throws Exception {
        Shard shard = createShardWithSegments(4);
        Future<Void> future = compactionManager.compactAsync(shard, Set.of(1, 2));
        future.get();
        assertEquals(1, shard.getSegments().size());
    }

    @Test
    void testStartStop() {
        assertFalse(compactionManager.isRunning());
        compactionManager.start();
        assertTrue(compactionManager.isRunning());
        compactionManager.stop();
        assertFalse(compactionManager.isRunning());
    }
}
