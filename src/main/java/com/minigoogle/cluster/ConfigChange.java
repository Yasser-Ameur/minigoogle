package com.minigoogle.cluster;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A Raft membership change ({@code ADD} or {@code REMOVE} of one server)
 * carried inside a Raft log entry's opaque payload.
 *
 * <p>Framing is big-endian, mirroring the {@code BinaryWriter} conventions of
 * the storage layer and reusing the {@code KvCommand} payload layout. The
 * leading op byte {@value #OP_CONFIG} is the config discriminator and never
 * collides with the KV ops ({@code 0x01} PUT, {@code 0x02} DELETE):
 * <pre>
 *   CONFIG = [0x03][1-byte change type: ADD=0x01 / REMOVE=0x02]
 *            [2-byte node-id length][node-id UTF-8]
 * </pre>
 * Decoding is strict: a malformed payload fails fast rather than being silently
 * mis-applied. Only one change may be pending at a time (one-server-at-a-time),
 * enforced by the consensus layer, not by this framing.
 */
public final class ConfigChange {

    /** Payload op discriminator for a config-change entry. */
    public static final byte OP_CONFIG = 0x03;

    /** Change type: add a server to the configuration. */
    public static final byte OP_ADD = 0x01;

    /** Change type: remove a server from the configuration. */
    public static final byte OP_REMOVE = 0x02;

    private final ChangeType type;
    private final String nodeId;

    public ConfigChange(ChangeType type, String nodeId) {
        this.type = type;
        this.nodeId = nodeId;
    }

    /** @return The change type (add or remove). */
    public ChangeType type() {
        return type;
    }

    /** @return The node ID the change targets. */
    public String nodeId() {
        return nodeId;
    }

    /**
     * @param payload The opaque log-entry payload.
     * @return Whether {@code payload} is a config-change frame. A minimum
     *         length of one byte is required; full validation happens in
     *         {@link #decode(byte[])}.
     */
    public static boolean isConfigFrame(byte[] payload) {
        return payload != null && payload.length >= 1 && payload[0] == OP_CONFIG;
    }

    /**
     * Encodes this change as a config frame.
     *
     * @throws IllegalArgumentException If the node ID exceeds 65535 UTF-8 bytes.
     */
    public byte[] encode() {
        byte[] idBytes = nodeId.getBytes(StandardCharsets.UTF_8);
        if (idBytes.length > 0xFFFF) {
            throw new IllegalArgumentException("Node ID too long: " + idBytes.length + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 2 + idBytes.length);
        buffer.put(OP_CONFIG);
        buffer.put(type == ChangeType.ADD ? OP_ADD : OP_REMOVE);
        buffer.putShort((short) idBytes.length);
        buffer.put(idBytes);
        return buffer.array();
    }

    /**
     * Decodes a config frame.
     *
     * @throws IllegalArgumentException If the payload is not a valid config frame.
     */
    public static ConfigChange decode(byte[] payload) {
        try {
            if (!isConfigFrame(payload) || payload.length < 4) {
                throw new IllegalArgumentException("Not a config-change frame");
            }
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            buffer.get();
            byte op = buffer.get();
            ChangeType type;
            if (op == OP_ADD) {
                type = ChangeType.ADD;
            } else if (op == OP_REMOVE) {
                type = ChangeType.REMOVE;
            } else {
                throw new IllegalArgumentException("Unknown config-change op: " + op);
            }
            int idLength = Short.toUnsignedInt(buffer.getShort());
            if (idLength < 1 || buffer.remaining() != idLength) {
                throw new IllegalArgumentException("Malformed config-change node ID length: " + idLength);
            }
            byte[] idBytes = new byte[idLength];
            buffer.get(idBytes);
            String nodeId = new String(idBytes, StandardCharsets.UTF_8);
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException("Trailing bytes after config-change frame");
            }
            return new ConfigChange(type, nodeId);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed config-change payload", e);
        }
    }

    /** The kind of membership change. */
    public enum ChangeType {
        ADD,
        REMOVE
    }

    @Override
    public String toString() {
        return "ConfigChange{" + type + " " + nodeId + '}';
    }
}
