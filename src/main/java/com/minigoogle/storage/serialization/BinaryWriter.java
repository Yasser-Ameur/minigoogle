package com.minigoogle.storage.serialization;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Low-level binary writer that serializes primitives and strings
 * to a {@link FileChannel} using a reusable {@link ByteBuffer}.
 *
 * <p>All multi-byte values are written in big-endian (Java default) byte order.
 * Strings are length-prefixed with a 2-byte unsigned length followed by UTF-8 bytes.</p>
 */
public class BinaryWriter implements AutoCloseable {

    private static final int DEFAULT_BUFFER_CAPACITY = 8192;

    private final FileChannel channel;
    private final ByteBuffer buffer;
    private long bytesWritten;

    public BinaryWriter(Path path) throws IOException {
        this(path, DEFAULT_BUFFER_CAPACITY);
    }

    public BinaryWriter(Path path, int bufferCapacity) throws IOException {
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        this.buffer = ByteBuffer.allocate(bufferCapacity);
        this.bytesWritten = 0;
    }

    /**
     * Writes a 4-byte signed integer.
     */
    public void writeInt(int value) throws IOException {
        ensureCapacity(Integer.BYTES);
        buffer.putInt(value);
    }

    /**
     * Writes an 8-byte signed long.
     */
    public void writeLong(long value) throws IOException {
        ensureCapacity(Long.BYTES);
        buffer.putLong(value);
    }

    /**
     * Writes a single byte.
     */
    public void writeByte(byte value) throws IOException {
        ensureCapacity(1);
        buffer.put(value);
    }

    /**
     * Writes a length-prefixed UTF-8 string.
     * Format: [length (2 bytes)] [UTF-8 bytes].
     */
    public void writeString(String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) {
            throw new IllegalArgumentException("String too long for 2-byte length prefix: " + bytes.length);
        }
        ensureCapacity(Short.BYTES);
        buffer.putShort((short) bytes.length);
        writeBytes(bytes);
    }

    /**
     * Writes raw bytes, flushing the internal buffer as needed.
     */
    public void writeBytes(byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            int remaining = buffer.remaining();
            int toWrite = Math.min(remaining, bytes.length - offset);
            buffer.put(bytes, offset, toWrite);
            offset += toWrite;
            if (!buffer.hasRemaining()) {
                flush();
            }
        }
    }

    /**
     * Returns the total number of bytes written to the channel so far
     * (including bytes still in the buffer).
     */
    public long position() {
        return bytesWritten + buffer.position();
    }

    /**
     * Flushes any buffered data to the underlying channel.
     */
    public void flush() throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            bytesWritten += channel.write(buffer);
        }
        buffer.clear();
    }

    @Override
    public void close() throws IOException {
        flush();
        channel.close();
    }

    private void ensureCapacity(int needed) throws IOException {
        if (buffer.remaining() < needed) {
            flush();
        }
    }
}
