package com.minigoogle.storage.segment;

import com.minigoogle.storage.mmap.MemoryMappedIndex;

import java.io.IOException;

/**
 * Reads from an existing immutable segment.
 */
public class SegmentReader {

    private final Segment segment;
    private final MemoryMappedIndex index;

    public SegmentReader(Segment segment) throws IOException {
        this.segment = segment;
        this.index = new MemoryMappedIndex(segment.getPostingsFile());
    }

    public Segment getSegment() {
        return segment;
    }

    public MemoryMappedIndex getIndex() {
        return index;
    }
}

