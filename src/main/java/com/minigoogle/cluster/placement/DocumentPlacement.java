package com.minigoogle.cluster.placement;

import com.minigoogle.cluster.ConsistentHashRing;

import java.util.List;

/**
 * Resolves which cluster nodes own a document, by consulting the consistent
 * hash ring for a document's URL.
 *
 * <p>The ring may currently hold fewer physical nodes than the configured
 * replication factor (a small or partially-joined cluster), in which case
 * {@link #owners(String)} returns every node on the ring rather than padding
 * the list with repeats.
 */
public final class DocumentPlacement {

    private final ConsistentHashRing ring;
    private final int replicationFactor;

    public DocumentPlacement(ConsistentHashRing ring, int replicationFactor) {
        this.ring = ring;
        this.replicationFactor = replicationFactor;
    }

    /**
     * @return The node IDs that own {@code url}, ring order (closest first),
     *         capped at {@code min(replicationFactor, ring.nodeCount())}.
     */
    public List<String> owners(String url) {
        int count = Math.min(replicationFactor, ring.nodeCount());
        return ring.getNodes(url, count);
    }

    public int replicationFactor() {
        return replicationFactor;
    }
}
