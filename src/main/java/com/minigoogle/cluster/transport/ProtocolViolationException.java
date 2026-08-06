package com.minigoogle.cluster.transport;

/**
 * Thrown when an incoming cluster message violates the wire protocol,
 * e.g. an unsupported {@link ClusterProtocol#PROTOCOL_VERSION} or a
 * correlation ID that does not match the request it responds to.
 */
public class ProtocolViolationException extends RuntimeException {

    public ProtocolViolationException(String message) {
        super(message);
    }

    public ProtocolViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
