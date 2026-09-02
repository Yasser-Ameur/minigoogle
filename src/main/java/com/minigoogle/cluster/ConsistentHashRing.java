package com.minigoogle.cluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
 *
 * <p>The ring itself ({@code TreeMap}) is guarded by a
 * {@link ReentrantReadWriteLock}: {@link #addNode(String)} and
 * {@link #removeNode(String)} take the write lock, while every read
 * ({@link #getNode(String)}, {@link #getNodes(String, int)}, {@link #nodes()},
 * counts) takes the read lock. Without this, a lookup racing a membership
 * change could observe a {@code TreeMap} mid-mutation.</p>
 */
public class ConsistentHashRing {

    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final int virtualNodesPerPhysical;
    private final Map<String, List<Long>> nodePositions = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public ConsistentHashRing(int virtualNodesPerPhysical) {
        this.virtualNodesPerPhysical = virtualNodesPerPhysical;
    }

    public ConsistentHashRing() {
        this(150);
    }

    /**
     * Adds a node to the ring with virtual nodes.
     */
    public void addNode(String nodeId) {
        List<Long> positions = new ArrayList<>();
        for (int i = 0; i < virtualNodesPerPhysical; i++) {
            long hash = hash(nodeId + "#" + i);
            positions.add(hash);
        }
        lock.writeLock().lock();
        try {
            for (long pos : positions) {
                ring.put(pos, nodeId);
            }
            nodePositions.put(nodeId, positions);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes a node from the ring.
     */
    public void removeNode(String nodeId) {
        lock.writeLock().lock();
        try {
            List<Long> positions = nodePositions.remove(nodeId);
            if (positions != null) {
                for (long pos : positions) {
                    ring.remove(pos);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the node responsible for a given key.
     *
     * @param key The key to look up.
     * @return The node ID, or null if the ring is empty.
     */
    public String getNode(String key) {
        long hash = hash(key);
        lock.readLock().lock();
        try {
            if (ring.isEmpty()) return null;
            Map.Entry<Long, String> entry = ring.ceilingEntry(hash);
            if (entry == null) {
                // Wrap around to the first node
                entry = ring.firstEntry();
            }
            return entry.getValue();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the N nodes responsible for a key (for replication).
     */
    public List<String> getNodes(String key, int count) {
        long hash = hash(key);
        lock.readLock().lock();
        try {
            if (ring.isEmpty()) return List.of();
            List<String> nodes = new ArrayList<>();

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
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * @return The number of physical nodes on the ring.
     */
    public int nodeCount() {
        return nodePositions.size();
    }

    public List<String> getAllNodes() {
        return new ArrayList<>(nodePositions.keySet());
    }

    /**
     * @return An immutable snapshot of the physical node IDs currently on the
     *         ring. Unlike {@link #getAllNodes()}, the returned set has no
     *         defined order.
     */
    public Set<String> nodes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(nodePositions.keySet()));
    }

    /**
     * @return The total number of virtual nodes on the ring.
     */
    public int virtualNodeCount() {
        lock.readLock().lock();
        try {
            return ring.size();
        } finally {
            lock.readLock().unlock();
        }
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
