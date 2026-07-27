package com.minigoogle.storage.serialization;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Low-level binary reader that deserializes primitives and strings
 * from a {@link ByteBuffer} (typically a {@link java.nio.MappedByteBuffer}).
 *
 * <p>All multi-byte values are read in big-endian (Java default) byte order.
 * Strings are expected in length-prefixed format: 2-byte unsigned length followed by UTF-8 bytes.</p>
 */
public class BinaryReader {

    private final ByteBuffer buffer;

    public BinaryReader(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * Reads a 4-byte signed integer.
     */
    public int readInt() {
        return buffer.getInt();
    }

    /**
     * Reads an 8-byte signed long.
     */
    public long readLong() {
        return buffer.getLong();
    }

    /**
     * Reads a single byte.
     */
    public byte readByte() {
        return buffer.get();
    }

    /**
     * Reads a length-prefixed UTF-8 string.
     */
    public String readString() {
        int length = Short.toUnsignedInt(buffer.getShort());
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Returns true if there are remaining bytes to read.
     */
    public boolean hasRemaining() {
        return buffer.hasRemaining();
    }

    /**
     * Returns the current position in the buffer.
     */
    public int position() {
        return buffer.position();
    }

    /**
     * Sets the buffer position.
     */
    public void position(int newPosition) {
        buffer.position(newPosition);
    }

    /**
     * Returns the underlying buffer (for advanced operations like slicing).
     */
    public ByteBuffer getBuffer() {
        return buffer;
    }
}
