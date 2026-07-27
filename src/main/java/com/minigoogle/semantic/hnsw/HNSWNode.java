package com.minigoogle.semantic.hnsw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A node in the HNSW (Hierarchical Navigable Small World) graph.
 *
 * <p>Each node stores a vector and maintains separate connection lists
 * for each layer of the hierarchical graph. Higher layers provide
 * long-range navigation while lower layers provide fine-grained local
 * connections.</p>
 */
public class HNSWNode {

    private final int id;
    private final double[] vector;
    private final Map<Integer, List<Integer>> connections;

    /**
     * Creates a new HNSW node with no initial connections.
     *
     * @param id     The unique integer identifier for this node.
     * @param vector The embedding vector associated with this node.
     */
    public HNSWNode(int id, double[] vector) {
        this.id = id;
        this.vector = vector.clone();
        this.connections = new HashMap<>();
    }

    /**
     * Adds a directed connection from this node to another node at the specified layer.
     *
     * @param layer  The graph layer (0-based).
     * @param nodeId The target node ID.
     */
    public void addConnection(int layer, int nodeId) {
        connections.computeIfAbsent(layer, k -> new ArrayList<>()).add(nodeId);
    }

    /**
     * Returns an unmodifiable view of the connections at the specified layer.
     *
     * @param layer The graph layer.
     * @return The list of connected node IDs at that layer, or an empty list.
     */
    public List<Integer> getConnections(int layer) {
        return connections.getOrDefault(layer, Collections.emptyList());
    }

    /**
     * Returns the highest layer in which this node has connections.
     *
     * @return The maximum layer index, or -1 if no connections exist.
     */
    public int maxLayer() {
        return connections.keySet().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(-1);
    }

    /**
     * Returns the unique identifier of this node.
     *
     * @return The node ID.
     */
    public int getId() {
        return id;
    }

    /**
     * Returns a copy of the embedding vector.
     *
     * @return A new array containing the vector data.
     */
    public double[] getVector() {
        return vector.clone();
    }
}
