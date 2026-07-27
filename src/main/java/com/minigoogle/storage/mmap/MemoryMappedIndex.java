package com.minigoogle.storage.mmap;

import com.minigoogle.core.storage.IndexReader;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.storage.postings.PostingReader;
import com.minigoogle.storage.serialization.BinaryReader;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Memory-mapped file reader for the postings index.
 * Maps the postings binary file into memory and provides offset-based access
 * to individual posting lists via {@link PostingReader}. Implements
 * {@link AutoCloseable} to release the underlying file channel.
 */
public class MemoryMappedIndex implements IndexReader, AutoCloseable {
    
    private final FileChannel channel;
    private final MappedByteBuffer mmap;
    private final PostingReader postingReader;
    
    public MemoryMappedIndex(Path postingsPath) throws IOException {
        this.channel = FileChannel.open(postingsPath, StandardOpenOption.READ);
        this.mmap = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
        this.postingReader = new PostingReader();
    }
    
    @Override
    public PostingList readPostingList(long offset) throws IOException {
        // Create a new BinaryReader positioned at the requested offset
        // We can just duplicate the buffer to allow concurrent reads
        java.nio.ByteBuffer duplicated = mmap.duplicate();
        // limit is Integer.MAX_VALUE, offset must be within integer range for MappedByteBuffer
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Offset too large for standard MappedByteBuffer: " + offset);
        }
        duplicated.position((int) offset);
        BinaryReader reader = new BinaryReader(duplicated);
        return postingReader.read(reader);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
