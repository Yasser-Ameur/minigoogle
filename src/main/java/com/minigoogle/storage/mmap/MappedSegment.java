package com.minigoogle.storage.mmap;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

public class MappedSegment implements AutoCloseable {

    private final Path filePath;
    private final long offset;
    private final long size;
    private final MappedByteBuffer buffer;
    private final RandomAccessFile file;

    public MappedSegment(Path filePath, long offset, long size) throws IOException {
        if (size < 0) {
            throw new IllegalArgumentException("Size must be non-negative");
        }
        this.filePath = filePath;
        this.offset = offset;
        this.size = size;
        this.file = new RandomAccessFile(filePath.toFile(), "r");
        this.buffer = this.file.getChannel().map(
                FileChannel.MapMode.READ_ONLY, offset, size);
    }

    public int readInt(long position) {
        checkBounds(position, Integer.BYTES);
        return buffer.getInt((int) position);
    }

    public long readLong(long position) {
        checkBounds(position, Long.BYTES);
        return buffer.getLong((int) position);
    }

    public byte[] readBytes(long position, int length) {
        checkBounds(position, length);
        byte[] result = new byte[length];
        buffer.position((int) position);
        buffer.get(result);
        return result;
    }

    public Path filePath() { return filePath; }
    public long offset() { return offset; }
    public long size() { return size; }

    private void checkBounds(long position, int length) {
        if (position < 0) {
            throw new IndexOutOfBoundsException("Position must be non-negative: " + position);
        }
        if (position + length > size) {
            throw new IndexOutOfBoundsException(
                    "Read of " + length + " bytes at position " + position
                            + " exceeds segment size " + size);
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
