package com.minigoogle.cluster.transport;

/**
 * Base contract for every cluster RPC message on the wire.
 *
 * <p>Every request and response record carries this metadata so that messages
 * are self-describing, traceable, and version-checkable without an envelope
 * wrapper. Records satisfy the interface through their canonical components.
 *
 * <p>Component semantics:
 * <ul>
 *   <li>{@code protocolVersion} — the wire protocol version ({@link ClusterProtocol#PROTOCOL_VERSION}).</li>
 *   <li>{@code requestId} — unique ID identifying the logical request, propagated to the response.</li>
 *   <li>{@code correlationId} — unique ID per request/response pair; responses echo the request's value so callers can match asynchronous replies.</li>
 *   <li>{@code sourceNodeId} — the node that produced this message.</li>
 *   <li>{@code timestamp} — epoch milliseconds at message creation.</li>
 * </ul>
 */
public interface ClusterMessage {

    int protocolVersion();

    String requestId();

    String correlationId();

    String sourceNodeId();

    long timestamp();
}
