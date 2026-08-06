package com.minigoogle.cluster.state;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Envelope for replicated key-value commands carried inside a Raft log entry's
 * opaque payload.
 *
 * <p>Framing is big-endian, mirroring the {@code BinaryWriter} conventions of
 * the storage layer:
 * <pre>
 *   PUT    = [0x01][2-byte key length][key UTF-8][4-byte value length][value]
 *   DELETE = [0x02][2-byte key length][key UTF-8]
 * </pre>
 * Decoding is strict: a malformed payload fails fast rather than being silently
 * mis-applied.
 */
public final class KvCommand {

    public static final byte OP_PUT = 0x01;
    public static final byte OP_DELETE = 0x02;

    private KvCommand() {
    }

    /**
     * Encodes a {@code PUT} command.
     *
     * @param key   The key, up to 65535 UTF-8 bytes.
     * @param value The value bytes.
     */
    public static byte[] encodePut(String key, byte[] value) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        checkKeyLength(keyBytes);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 2 + keyBytes.length + 4 + value.length);
        buffer.put(OP_PUT);
        buffer.putShort((short) keyBytes.length);
        buffer.put(keyBytes);
        buffer.putInt(value.length);
        buffer.put(value);
        return buffer.array();
    }

    /**
     * Encodes a {@code DELETE} command.
     *
     * @param key The key, up to 65535 UTF-8 bytes.
     */
    public static byte[] encodeDelete(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        checkKeyLength(keyBytes);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 2 + keyBytes.length);
        buffer.put(OP_DELETE);
        buffer.putShort((short) keyBytes.length);
        buffer.put(keyBytes);
        return buffer.array();
    }

    /**
     * Decodes a command payload.
     *
     * @throws IllegalArgumentException If the payload is not a valid command.
     */
    public static DecodedCommand decode(byte[] payload) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte op = buffer.get();
            int keyLength = Short.toUnsignedInt(buffer.getShort());
            byte[] keyBytes = new byte[keyLength];
            buffer.get(keyBytes);
            String key = new String(keyBytes, StandardCharsets.UTF_8);
            byte[] value = null;
            if (op == OP_PUT) {
                int valueLength = buffer.getInt();
                if (valueLength < 0 || buffer.remaining() != valueLength) {
                    throw new IllegalArgumentException("Malformed PUT value length: " + valueLength);
                }
                value = new byte[valueLength];
                buffer.get(value);
            } else if (op != OP_DELETE) {
                throw new IllegalArgumentException("Unknown KV command op: " + op);
            }
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException("Trailing bytes after KV command");
            }
            return new DecodedCommand(op, key, value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed KV command payload", e);
        }
    }

    /**
     * A decoded {@link KvCommand}: the op code, key, and for {@code PUT} the
     * value bytes ({@code null} for {@code DELETE}).
     */
    public record DecodedCommand(byte op, String key, byte[] value) {
    }

    private static void checkKeyLength(byte[] keyBytes) {
        if (keyBytes.length > 0xFFFF) {
            throw new IllegalArgumentException("Key too long: " + keyBytes.length + " bytes");
        }
    }
}
