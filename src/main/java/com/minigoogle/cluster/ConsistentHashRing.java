package com.minigoogle.cluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consistent hash ring for distributing data across cluster nodes.
 *
 * Per ARCHITECTURE.md Ch14:
 *   Consistent hashing minimizes data movement when nodes join or leave.
 *   Each node is mapped to multiple points on the ring.
 *   A key is assigned to the next node clockwise from its hash position.
 *
 * When a node is added, only the keys between it and its predecessor
 * need to be moved — not all keys.
 */
public class ConsistentHashRing {

    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final int virtualNodesPerPhysical;
    private final Map<String, List<Long>> nodePositions = new ConcurrentHashMap<>();

    public ConsistentHashRing(int virtualNodesPerPhysical) {
        this.virtualNodesPerPhysical = virtualNodesPerPhysical;
    }

    public ConsistentHashRing() {
        this(150);
    }

    /**
     * Adds a node to the ring with virtual nodes.
     */
    public synchronized void addNode(String nodeId) {
        List<Long> positions = new ArrayList<>();
        for (int i = 0; i < virtualNodesPerPhysical; i++) {
            long hash = hash(nodeId + "#" + i);
            ring.put(hash, nodeId);
            positions.add(hash);
        }
        nodePositions.put(nodeId, positions);
    }

    /**
     * Removes a node from the ring.
     */
    public synchronized void removeNode(String nodeId) {
        List<Long> positions = nodePositions.remove(nodeId);
        if (positions != null) {
            for (long pos : positions) {
                ring.remove(pos);
            }
        }
    }

    /**
     * Returns the node responsible for a given key.
     *
     * @param key The key to look up.
     * @return The node ID, or null if the ring is empty.
     */
    public String getNode(String key) {
        if (ring.isEmpty()) return null;
        long hash = hash(key);
        Map.Entry<Long, String> entry = ring.ceilingEntry(hash);
        if (entry == null) {
            // Wrap around to the first node
            entry = ring.firstEntry();
        }
        return entry.getValue();
    }

    /**
     * Returns the N nodes responsible for a key (for replication).
     */
    public List<String> getNodes(String key, int count) {
        if (ring.isEmpty()) return List.of();
        List<String> nodes = new ArrayList<>();
        long hash = hash(key);

        Map.Entry<Long, String> entry = ring.ceilingEntry(hash);
        if (entry == null) {
            entry = ring.firstEntry();
        }

        // Walk clockwise, collecting unique node IDs
        var cursor = ring.tailMap(entry.getKey(), true).entrySet().iterator();
        while (cursor.hasNext() && nodes.size() < count) {
            String node = cursor.next().getValue();
            if (!nodes.contains(node)) {
                nodes.add(node);
            }
        }
        // Wrap around if needed
        cursor = ring.entrySet().iterator();
        while (cursor.hasNext() && nodes.size() < count) {
            String node = cursor.next().getValue();
            if (!nodes.contains(node)) {
                nodes.add(node);
            }
        }

        return Collections.unmodifiableList(nodes);
    }

    /**
     * @return The number of physical nodes on the ring.
     */
    public int nodeCount() {
        return nodePositions.size();
    }

    /**
     * @return The total number of virtual nodes on the ring.
     */
    public int virtualNodeCount() {
        return ring.size();
    }

    /**
     * Computes a consistent hash using MD5 for uniform distribution.
     */
    private long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            // Use the first 8 bytes as a long
            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xFF);
            }
            return hash;
        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple hash
            return key.hashCode() & 0xFFFFFFFFL;
        }
    }
}
