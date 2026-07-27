package com.minigoogle.storage.allocator;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedDeque;

public class BufferPool {

    private final int bufferSize;
    private final ConcurrentLinkedDeque<ByteBuffer> pool = new ConcurrentLinkedDeque<>();

    public BufferPool(int bufferSize, int poolSize) {
        this.bufferSize = bufferSize;
        for (int i = 0; i < poolSize; i++) {
            pool.addLast(ByteBuffer.allocate(bufferSize));
        }
    }

    public ByteBuffer acquire() {
        ByteBuffer buffer = pool.pollFirst();
        if (buffer != null) {
            buffer.clear();
            return buffer;
        }
        return ByteBuffer.allocate(bufferSize);
    }

    public void release(ByteBuffer buffer) {
        if (buffer != null) {
            buffer.clear();
            pool.addLast(buffer);
        }
    }

    public int bufferSize() {
        return bufferSize;
    }

    public int available() {
        return pool.size();
    }

    public int poolSize() {
        return pool.size();
    }
}
