package com.minigoogle.semantic.hnsw;

import com.minigoogle.semantic.EmbeddingGenerator;
import com.minigoogle.semantic.VectorIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hierarchical Navigable Small World graph for approximate nearest neighbor search.
 *
 * <p>HNSW builds a multi-layer graph where:
 * <ul>
 *   <li>Layer 0 contains all nodes with dense local connections.</li>
 *   <li>Higher layers contain progressively fewer nodes with longer-range links.</li>
 * </ul>
 * Search begins at the topmost layer and descends greedily, using a beam
 * search at each level with configurable {@code ef} width.</p>
 *
 * <p>Node layer assignment follows the standard HNSW exponential decay:
 * a node is promoted to layer {@code l} with probability {@code 1 / maxConnections}^l.</p>
 */
public class HNSWGraph {

    private final int dimension;
    private final int maxConnections;
    private final int maxLayers;
    private final Map<Integer, HNSWNode> nodes;
    private final List<Integer> entryPoints;
    private final HNSWSearcher searcher;
    private final Random rng;

    /**
     * Constructs an empty HNSW graph.
     *
     * @param dimension      The embedding dimension.
     * @param maxConnections The maximum number of connections per node at each layer (M parameter).
     * @param maxLayers      The maximum number of layers in the graph.
     */
    public HNSWGraph(int dimension, int maxConnections, int maxLayers) {
        this(dimension, maxConnections, maxLayers, new Random(42));
    }

    /**
     * Constructs an empty HNSW graph with a specified random seed.
     *
     * @param dimension      The embedding dimension.
     * @param maxConnections The maximum number of connections per node at each layer.
     * @param maxLayers      The maximum number of layers in the graph.
     * @param rng            The random number generator for layer assignment.
     */
    public HNSWGraph(int dimension, int maxConnections, int maxLayers, Random rng) {
        this.dimension = dimension;
        this.maxConnections = maxConnections;
        this.maxLayers = maxLayers;
        this.nodes = new ConcurrentHashMap<>();
        this.entryPoints = new ArrayList<>();
        this.rng = rng;
        this.searcher = new HNSWSearcher(this);
    }

    /**
     * Inserts a new node into the graph with a randomly assigned layer.
     *
     * @param id     The unique document ID.
     * @param vector The embedding vector.
     */
    public void insert(int id, double[] vector) {
        if (vector.length != dimension) {
            throw new IllegalArgumentException(
                    "Vector dimension mismatch: expected " + dimension + " but got " + vector.length);
        }

        HNSWNode node = new HNSWNode(id, vector);
        int assignedLayer = randomLayer();

        nodes.put(id, node);

        synchronized (this) {
            if (entryPoints.isEmpty()) {
                entryPoints.add(id);
                return;
            }
        }

        // Greedy descent: find nearest node at each level down to layer 0
        List<Integer> currentBest = new ArrayList<>(entryPoints);

        for (int layer = maxLayers; layer > assignedLayer; layer--) {
            currentBest = beamSearchAtLayer(currentBest, vector, 1, layer);
        }

        // Insert at each level from assignedLayer down to 0
        for (int layer = Math.min(assignedLayer, maxLayers - 1); layer >= 0; layer--) {
            List<Integer> neighbors = beamSearchAtLayer(currentBest, vector, maxConnections * 2, layer);

            for (int neighborId : neighbors) {
                HNSWNode neighbor = nodes.get(neighborId);
                if (neighbor != null) {
                    neighbor.addConnection(layer, id);
                    node.addConnection(layer, neighborId);
                }
            }

            currentBest = neighbors;
        }

        synchronized (this) {
            if (assignedLayer >= maxLayers - 1 && !entryPoints.contains(id)) {
                entryPoints.clear();
                entryPoints.add(id);
            }
        }
    }

    /**
     * Performs approximate nearest neighbor search.
     *
     * @param query The query vector.
     * @param k     Number of results to return.
     * @param ef    Search beam width (higher = more accurate, slower).
     * @return Up to {@code k} nearest results sorted by descending similarity.
     */
    public List<VectorIndex.VectorResult> search(double[] query, int k, int ef) {
        if (query.length != dimension) {
            throw new IllegalArgumentException(
                    "Query dimension mismatch: expected " + dimension + " but got " + query.length);
        }
        return searcher.search(query, k, ef);
    }

    /**
     * Returns the number of nodes in the graph.
     *
     * @return The node count.
     */
    public int size() {
        return nodes.size();
    }

    /**
     * Returns the embedding dimension.
     *
     * @return The dimension.
     */
    public int getDimension() {
        return dimension;
    }

    /**
     * Removes all nodes and connections from the graph.
     */
    public void clear() {
        nodes.clear();
        entryPoints.clear();
    }

    /**
     * Returns the node with the given ID, or null if not found.
     *
     * @param id The node ID.
     * @return The node, or null.
     */
    HNSWNode getNode(int id) {
        return nodes.get(id);
    }

    /**
     * Returns the current entry point IDs.
     *
     * @return An unmodifiable view of the entry points.
     */
    List<Integer> getEntryPoints() {
        synchronized (this) {
            return List.copyOf(entryPoints);
        }
    }

    /**
     * Returns all node IDs in the graph.
     *
     * @return An unmodifiable set of node IDs.
     */
    java.util.Set<Integer> getAllNodeIds() {
        return java.util.Collections.unmodifiableSet(nodes.keySet());
    }

    /**
     * Returns the maximum connections parameter.
     *
     * @return The max connections value.
     */
    int getMaxConnections() {
        return maxConnections;
    }

    /**
     * Returns the maximum layer count.
     *
     * @return The max layers value.
     */
    int getMaxLayers() {
        return maxLayers;
    }

    private List<Integer> beamSearchAtLayer(List<Integer> startPoints, double[] query,
                                            int width, int layer) {
        if (startPoints.isEmpty()) {
            return List.of();
        }

        PriorityQueueCandidate heap = new PriorityQueueCandidate(width);
        java.util.Set<Integer> visited = new java.util.HashSet<>();

        for (int epId : startPoints) {
            HNSWNode ep = nodes.get(epId);
            if (ep == null) continue;
            double sim = EmbeddingGenerator.cosineSimilarity(query, ep.getVector());
            heap.offer(epId, sim);
            visited.add(epId);
        }

        boolean improved = true;
        while (improved) {
            improved = false;
            List<int[]> candidates = new ArrayList<>(heap.getCurrent());
            for (int[] c : candidates) {
                int cId = c[0];
                HNSWNode cNode = nodes.get(cId);
                if (cNode == null) continue;

                for (int neighborId : cNode.getConnections(layer)) {
                    if (visited.contains(neighborId)) continue;
                    visited.add(neighborId);

                    HNSWNode neighbor = nodes.get(neighborId);
                    if (neighbor == null) continue;
                    double sim = EmbeddingGenerator.cosineSimilarity(query, neighbor.getVector());

                    if (heap.size() < width || sim > heap.peekScore()) {
                        heap.offer(neighborId, sim);
                        improved = true;
                    }
                }
            }
        }

        return heap.toSortedIds();
    }

    private int randomLayer() {
        double uniform = rng.nextDouble();
        return (int) (-Math.log(uniform) / Math.log(maxConnections));
    }

    /**
     * A simple bounded priority queue for candidates.
     */
    private static class PriorityQueueCandidate {
        private final int capacity;
        private final java.util.TreeMap<Double, java.util.List<Integer>> map = new java.util.TreeMap<>();
        private int size = 0;

        PriorityQueueCandidate(int capacity) {
            this.capacity = capacity;
        }

        void offer(int id, double score) {
            if (size >= capacity) {
                Double lowestKey = map.firstKey();
                java.util.List<Integer> lowestList = map.get(lowestKey);
                if (lowestList != null && !lowestList.isEmpty()) {
                    lowestList.remove(0);
                    size--;
                    if (lowestList.isEmpty()) {
                        map.pollFirstEntry();
                    }
                }
            }
            map.computeIfAbsent(score, k -> new java.util.ArrayList<>()).add(id);
            size++;
        }

        double peekScore() {
            return map.firstKey();
        }

        int size() {
            return size;
        }

        List<int[]> getCurrent() {
            List<int[]> result = new ArrayList<>();
            for (Map.Entry<Double, java.util.List<Integer>> entry : map.entrySet()) {
                for (int id : entry.getValue()) {
                    result.add(new int[]{id, 0});
                }
            }
            return result;
        }

        List<Integer> toSortedIds() {
            List<Integer> result = new ArrayList<>();
            for (Map.Entry<Double, java.util.List<Integer>> entry : map.descendingMap().entrySet()) {
                result.addAll(entry.getValue());
            }
            return result;
        }
    }
}
