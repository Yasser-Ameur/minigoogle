package com.minigoogle.cluster.state;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Envelope for replicated key-value commands carried inside a Raft log entry's
 * opaque payload.
 *
 * <p>Framing is big-endian, mirroring the {@code BinaryWriter} conventions of
 * the storage layer:
 * <pre>
 *   PUT    = [0x01][2-byte key length][key UTF-8][4-byte value length][value]
 *   DELETE = [0x02][2-byte key length][key UTF-8]
 *   TXN    = [0x04][4-byte op count][(op)(key)(value?)]*
 * </pre>
 * A transaction is a single log entry whose sub-ops apply atomically: because
 * Raft commits an entire entry or none of it, a TXN frame either applies
 * wholesale or not at all. Decoding is strict: a malformed payload fails fast
 * rather than being silently mis-applied.
 */
public final class KvCommand {

    public static final byte OP_PUT = 0x01;
    public static final byte OP_DELETE = 0x02;
    public static final byte OP_TXN = 0x04;

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
     * Encodes a multi-op transaction: a list of {@link TxnOp} sub-commands that
     * apply atomically as a single committed log entry. {@code PUT} ops carry a
     * value, {@code DELETE} ops a {@code null} value.
     *
     * @param ops The sub-ops, applied in list order.
     * @throws IllegalArgumentException If {@code ops} is empty or oversized.
     */
    public static byte[] encodeTxn(List<TxnOp> ops) {
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("Transaction must contain at least one op");
        }
        int size = 1 + 4;
        for (TxnOp op : ops) {
            byte[] keyBytes = op.key().getBytes(StandardCharsets.UTF_8);
            checkKeyLength(keyBytes);
            size += 1 + 2 + keyBytes.length;
            if (op.op() == OP_PUT) {
                if (op.value() == null) {
                    throw new IllegalArgumentException("PUT op requires a value");
                }
                size += 4 + op.value().length;
            } else if (op.op() != OP_DELETE) {
                throw new IllegalArgumentException("Unknown TXN op: " + op.op());
            }
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.put(OP_TXN);
        buffer.putInt(ops.size());
        for (TxnOp op : ops) {
            byte[] keyBytes = op.key().getBytes(StandardCharsets.UTF_8);
            buffer.put(op.op());
            buffer.putShort((short) keyBytes.length);
            buffer.put(keyBytes);
            if (op.op() == OP_PUT) {
                buffer.putInt(op.value().length);
                buffer.put(op.value());
            }
        }
        return buffer.array();
    }

    /**
     * @return {@code true} if the payload is a transaction frame.
     */
    public static boolean isTxnFrame(byte[] payload) {
        return payload != null && payload.length >= 1 && payload[0] == OP_TXN;
    }

    /**
     * Decodes a transaction payload into its ordered sub-ops.
     *
     * @throws IllegalArgumentException If the payload is not a valid
     *                                  transaction frame.
     */
    public static List<DecodedCommand> decodeTxn(byte[] payload) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte op = buffer.get();
            if (op != OP_TXN) {
                throw new IllegalArgumentException("Not a transaction frame: " + op);
            }
            int count = buffer.getInt();
            if (count < 0 || count > 1_000_000) {
                throw new IllegalArgumentException("Invalid TXN op count: " + count);
            }
            List<DecodedCommand> ops = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ops.add(decodeSingle(buffer));
            }
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException("Trailing bytes after TXN frame");
            }
            return ops;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed TXN payload", e);
        }
    }

    /**
     * Decodes a command payload.
     *
     * @throws IllegalArgumentException If the payload is not a valid command.
     */
    public static DecodedCommand decode(byte[] payload) {
        try {
            if (isTxnFrame(payload)) {
                throw new IllegalArgumentException("Expected single command, got TXN frame");
            }
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            DecodedCommand command = decodeSingle(buffer);
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException("Trailing bytes after KV command");
            }
            return command;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed KV command payload", e);
        }
    }

    private static DecodedCommand decodeSingle(ByteBuffer buffer) {
        byte op = buffer.get();
        int keyLength = Short.toUnsignedInt(buffer.getShort());
        byte[] keyBytes = new byte[keyLength];
        buffer.get(keyBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);
        byte[] value = null;
        if (op == OP_PUT) {
            int valueLength = buffer.getInt();
            if (valueLength < 0 || valueLength > buffer.remaining()) {
                throw new IllegalArgumentException("Malformed PUT value length: " + valueLength);
            }
            value = new byte[valueLength];
            buffer.get(value);
        } else if (op != OP_DELETE) {
            throw new IllegalArgumentException("Unknown KV command op: " + op);
        }
        return new DecodedCommand(op, key, value);
    }

    /**
     * A decoded {@link KvCommand}: the op code, key, and for {@code PUT} the
     * value bytes ({@code null} for {@code DELETE}).
     */
    public record DecodedCommand(byte op, String key, byte[] value) {
    }

    /**
     * A single sub-op of a {@link #encodeTxn(List)} transaction.
     *
     * @param op    {@link #OP_PUT} or {@link #OP_DELETE}.
     * @param key   The key.
     * @param value The value for {@code PUT}, or {@code null} for {@code DELETE}.
     */
    public record TxnOp(byte op, String key, byte[] value) {
    }

    private static void checkKeyLength(byte[] keyBytes) {
        if (keyBytes.length > 0xFFFF) {
            throw new IllegalArgumentException("Key too long: " + keyBytes.length + " bytes");
        }
    }
}
