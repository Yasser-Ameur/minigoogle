package com.minigoogle.cluster.transport;

import java.util.UUID;

/**
 * Constants and helpers for the cluster wire protocol.
 *
 * <p>All cluster RPC messages must carry the current {@link #PROTOCOL_VERSION}.
 * Receivers validate incoming messages with {@link #validate(ClusterMessage)}
 * before processing, so a node with a newer protocol never misparses an
 * incompatible peer — it rejects the message explicitly.
 */
public final class ClusterProtocol {

    /**
     * The current wire protocol version. Bump whenever an on-the-wire
     * message layout changes incompatibly (new required field, removed
     * field, reordering, type change).
     */
    public static final int PROTOCOL_VERSION = 1;

    private ClusterProtocol() {
    }

    /**
     * Generates a unique ID for a request or correlation.
     *
     * @return A random UUID string.
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * @return The current time in epoch milliseconds.
     */
    public static long now() {
        return System.currentTimeMillis();
    }

    /**
     * Validates that a message uses the current protocol version.
     *
     * @param message The incoming message.
     * @throws ProtocolViolationException if the version does not match the current protocol.
     */
    public static void validate(ClusterMessage message) {
        validateVersion(message.protocolVersion());
    }

    /**
     * Validates a raw protocol version.
     *
     * @param protocolVersion The version carried on the wire.
     * @throws ProtocolViolationException if the version does not match the current protocol.
     */
    public static void validateVersion(int protocolVersion) {
        if (protocolVersion != PROTOCOL_VERSION) {
            throw new ProtocolViolationException(
                    "Unsupported protocol version: " + protocolVersion + " (expected " + PROTOCOL_VERSION + ")");
        }
    }
}
