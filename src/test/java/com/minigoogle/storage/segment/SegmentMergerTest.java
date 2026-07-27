package com.minigoogle.storage.segment;

import com.minigoogle.storage.filesystem.StorageLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for SegmentMerger functionality. */
class SegmentMergerTest {

    @TempDir
    Path tempDir;

    private StorageLayout layout;
    private SegmentMerger merger;

    @BeforeEach
    void setUp() {
        layout = new StorageLayout(tempDir);
        merger = new SegmentMerger(layout);
    }

    @Test
    void testMergeSingleSegmentReturnsItself() {
        Segment seg = new Segment("seg-1", tempDir.resolve("seg-1"), 100, 1024);
        List<Segment> segments = List.of(seg);
        Segment result = merger.merge(segments, 0);
        assertEquals(seg, result);
    }

    @Test
    void testMergeMultipleSegments() {
        Segment s1 = new Segment("seg-1", tempDir.resolve("seg-1"), 50, 512);
        Segment s2 = new Segment("seg-2", tempDir.resolve("seg-2"), 75, 768);
        List<Segment> segments = List.of(s1, s2);

        Segment result = merger.merge(segments, 0);
        assertNotNull(result);
        assertEquals(125, result.documentCount());
        assertEquals(1280, result.sizeInBytes());
    }

    @Test
    void testMergeEmptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> merger.merge(List.of(), 0));
    }

    @Test
    void testMergeAsyncCompletes() throws Exception {
        Segment s1 = new Segment("seg-1", tempDir.resolve("seg-1"), 10, 100);
        Segment s2 = new Segment("seg-2", tempDir.resolve("seg-2"), 20, 200);

        Future<Segment> future = merger.mergeAsync(List.of(s1, s2), 0);
        Segment result = future.get();
        assertNotNull(result);
        assertEquals(30, result.documentCount());
    }

    @Test
    void testMergedSegmentHasNewId() {
        Segment s1 = new Segment("seg-1", tempDir.resolve("seg-1"), 10, 100);
        Segment s2 = new Segment("seg-2", tempDir.resolve("seg-2"), 20, 200);

        Segment result = merger.merge(List.of(s1, s2), 0);
        assertNotEquals("seg-1", result.segmentId());
        assertNotEquals("seg-2", result.segmentId());
    }

    @Test
    void testShutdownDoesNotThrow() {
        merger.shutdown();
    }
}
