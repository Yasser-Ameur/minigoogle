package com.minigoogle.cluster.transport;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves cluster node ids to their internal RPC base URIs from static
 * configuration.
 *
 * <p>This is the production {@link NodeDirectory}: every cluster transport
 * (gossip, Raft, search dispatch) resolves a peer's address through it, so
 * without one {@code ClusterNode} cannot be constructed at all. Membership is
 * discovered at runtime by gossip; this directory only supplies the fixed
 * <em>addresses</em> of the configured peers, which is what a container or pod
 * environment provides via DNS.</p>
 *
 * <h2>Peer syntax</h2>
 * A comma-separated list, each entry in one of:
 * <pre>
 *   nodeId=http://host:port     explicit id and URI
 *   nodeId@host:port            explicit id, http assumed
 *   http://host:port            id defaults to the host
 *   host:port                   id defaults to the host, http assumed
 * </pre>
 * Entries are order-preserving and blank segments are ignored, so a trailing
 * comma in a compose file or ConfigMap is harmless.
 *
 * <p>In Kubernetes a StatefulSet gives every pod a stable DNS name, so peers are
 * typically written as {@code minigoogle-0=http://minigoogle-0.minigoogle:8081,...}.
 * In Docker Compose the service name serves the same purpose.</p>
 */
public final class StaticNodeDirectory implements NodeDirectory {

    private final Map<String, URI> byNodeId;

    private StaticNodeDirectory(Map<String, URI> byNodeId) {
        this.byNodeId = byNodeId;
    }

    /**
     * Parses a peer specification.
     *
     * @param peers comma-separated peer list; null or blank yields an empty
     *              directory (valid for a single-node cluster)
     * @throws IllegalArgumentException if an entry cannot be parsed, or if the
     *                                  same node id is given two addresses
     */
    public static StaticNodeDirectory parse(String peers) {
        Map<String, URI> parsed = new LinkedHashMap<>();
        if (peers == null || peers.isBlank()) {
            return new StaticNodeDirectory(parsed);
        }
        for (String rawEntry : peers.split(",")) {
            String entry = rawEntry.strip();
            if (entry.isEmpty()) {
                continue;
            }
            String nodeId;
            String address;
            int assign = entry.indexOf('=');
            int at = entry.indexOf('@');
            if (assign > 0) {
                nodeId = entry.substring(0, assign).strip();
                address = entry.substring(assign + 1).strip();
            } else if (at > 0 && !entry.contains("://")) {
                nodeId = entry.substring(0, at).strip();
                address = entry.substring(at + 1).strip();
            } else {
                address = entry;
                nodeId = null;
            }

            URI uri = toUri(address, entry);
            if (nodeId == null || nodeId.isEmpty()) {
                nodeId = uri.getHost();
            }
            if (nodeId == null || nodeId.isEmpty()) {
                throw new IllegalArgumentException(
                        "Cannot determine node id for peer entry '" + entry + "'");
            }
            URI previous = parsed.putIfAbsent(nodeId, uri);
            if (previous != null && !previous.equals(uri)) {
                throw new IllegalArgumentException("Peer '" + nodeId
                        + "' is configured with two addresses: " + previous + " and " + uri);
            }
        }
        return new StaticNodeDirectory(parsed);
    }

    private static URI toUri(String address, String entry) {
        String withScheme = address.contains("://") ? address : "http://" + address;
        URI uri;
        try {
            uri = URI.create(withScheme);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed peer entry '" + entry + "': " + e.getMessage(), e);
        }
        if (uri.getHost() == null || uri.getPort() <= 0) {
            throw new IllegalArgumentException(
                    "Peer entry '" + entry + "' must include a host and an explicit port");
        }
        // Normalize away any path: transports append their own endpoint paths.
        return URI.create(uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort());
    }

    /**
     * Returns a directory that also resolves this node's own id, so a node can
     * address itself through the same mechanism as its peers.
     *
     * @param selfId   this node's id
     * @param selfUri  this node's internal RPC base URI
     */
    public StaticNodeDirectory withSelf(String selfId, URI selfUri) {
        Map<String, URI> merged = new LinkedHashMap<>(byNodeId);
        merged.put(selfId, selfUri);
        return new StaticNodeDirectory(merged);
    }

    @Override
    public URI getBaseUri(String nodeId) {
        return byNodeId.get(nodeId);
    }

    /** @return every configured node id, in configuration order. */
    public Set<String> nodeIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(byNodeId.keySet()));
    }

    /** @return the number of configured nodes. */
    public int size() {
        return byNodeId.size();
    }

    @Override
    public String toString() {
        return "StaticNodeDirectory" + byNodeId;
    }
}
