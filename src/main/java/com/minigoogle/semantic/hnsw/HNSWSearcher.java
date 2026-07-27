package com.minigoogle.semantic.hnsw;

import com.minigoogle.semantic.EmbeddingGenerator;
import com.minigoogle.semantic.VectorIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Performs greedy best-first search on an {@link HNSWGraph}.
 *
 * <p>The search starts from the graph entry points at the highest layer and
 * descends layer by layer, performing beam search at each level. At layer 0,
 * the beam width is expanded to {@code ef} to collect final candidates which
 * are then trimmed to the top-k results.</p>
 */
public class HNSWSearcher {

    private final HNSWGraph graph;

    /**
     * Creates a searcher bound to the given graph.
     *
     * @param graph The HNSW graph to search.
     */
    public HNSWSearcher(HNSWGraph graph) {
        this.graph = graph;
    }

    /**
     * Performs approximate nearest neighbor search on the graph.
     *
     * @param query The query vector.
     * @param k     Number of results to return.
     * @param ef    Beam width for the search (must be >= k).
     * @return Up to {@code k} nearest results sorted by descending similarity.
     */
    public List<VectorIndex.VectorResult> search(double[] query, int k, int ef) {
        List<Integer> entryPoints = graph.getEntryPoints();
        if (entryPoints.isEmpty()) {
            return List.of();
        }

        int topLayer = graph.getMaxLayers() - 1;

        // Greedy descent from top layer to layer 1
        List<Integer> currentBest = entryPoints;
        for (int layer = topLayer; layer >= 1; layer--) {
            currentBest = beamSearch(query, currentBest, 1, layer);
        }

        // Full beam search at layer 0 with ef width
        List<Integer> candidates = beamSearch(query, currentBest, Math.max(ef, k), 0);

        // Score all candidates and return top-k
        MinHeap heap = new MinHeap(k);
        for (int nodeId : candidates) {
            HNSWNode node = graph.getNode(nodeId);
            if (node == null) continue;
            double score = EmbeddingGenerator.cosineSimilarity(query, node.getVector());
            heap.offer(nodeId, score);
        }

        // Also check any remaining neighbors at layer 0 that weren't in the initial beam
        java.util.Set<Integer> visited = new java.util.HashSet<>(candidates);
        for (int nodeId : candidates) {
            HNSWNode node = graph.getNode(nodeId);
            if (node == null) continue;
            for (int neighborId : node.getConnections(0)) {
                if (visited.contains(neighborId)) continue;
                visited.add(neighborId);
                HNSWNode neighbor = graph.getNode(neighborId);
                if (neighbor == null) continue;
                double score = EmbeddingGenerator.cosineSimilarity(query, neighbor.getVector());
                heap.offer(neighborId, score);
            }
        }

        List<VectorIndex.VectorResult> results = new ArrayList<>();
        while (!heap.isEmpty()) {
            Candidate c = heap.poll();
            HNSWNode node = graph.getNode(c.id);
            String metadata = null;
            results.add(new VectorIndex.VectorResult(c.id, c.score, metadata));
        }
        results.sort(Comparator.comparingDouble(VectorIndex.VectorResult::score).reversed());
        return results;
    }

    private List<Integer> beamSearch(double[] query, List<Integer> startPoints,
                                     int width, int layer) {
        if (startPoints.isEmpty()) {
            return List.of();
        }

        MinHeap candidates = new MinHeap(width);
        MaxHeap visited = new MaxHeap(width);
        Set<Integer> visitedIds = new HashSet<>();

        for (int epId : startPoints) {
            HNSWNode ep = graph.getNode(epId);
            if (ep == null) continue;
            double sim = EmbeddingGenerator.cosineSimilarity(query, ep.getVector());
            candidates.offer(epId, sim);
            visitedIds.add(epId);
        }

        while (!candidates.isEmpty()) {
            Candidate current = candidates.peek();

            // Early termination: if current candidate is worse than the worst in visited, stop
            if (!visited.isEmpty() && current.score < visited.peekScore()
                    && visited.size() >= width) {
                break;
            }
            candidates.poll();

            HNSWNode currentNode = graph.getNode(current.id);
            if (currentNode == null) continue;

            for (int neighborId : currentNode.getConnections(layer)) {
                if (visitedIds.contains(neighborId)) continue;
                visitedIds.add(neighborId);

                HNSWNode neighbor = graph.getNode(neighborId);
                if (neighbor == null) continue;
                double sim = EmbeddingGenerator.cosineSimilarity(query, neighbor.getVector());

                if (visited.size() < width || sim > visited.peekScore()) {
                    visited.offer(neighborId, sim);
                    candidates.offer(neighborId, sim);
                }
            }
        }

        List<Integer> result = new ArrayList<>();
        while (!visited.isEmpty()) {
            result.add(visited.poll().id);
        }
        return result;
    }

    private record Candidate(int id, double score) {
    }

    /**
     * Min-heap of candidates ordered by score (lowest on top for efficient pruning).
     */
    private static class MinHeap {
        private final PriorityQueue<Candidate> heap;
        private final int capacity;

        MinHeap(int capacity) {
            this.capacity = capacity;
            this.heap = new PriorityQueue<>(Comparator.comparingDouble(Candidate::score));
        }

        void offer(int id, double score) {
            heap.offer(new Candidate(id, score));
            while (heap.size() > capacity) {
                heap.poll();
            }
        }

        Candidate peek() {
            return heap.peek();
        }

        Candidate poll() {
            return heap.poll();
        }

        boolean isEmpty() {
            return heap.isEmpty();
        }

        int size() {
            return heap.size();
        }
    }

    /**
     * Max-heap for tracking visited nodes (highest score on top).
     */
    private static class MaxHeap {
        private final PriorityQueue<Candidate> heap;
        private final int capacity;

        MaxHeap(int capacity) {
            this.capacity = capacity;
            this.heap = new PriorityQueue<>((a, b) -> Double.compare(b.score, a.score));
        }

        void offer(int id, double score) {
            heap.offer(new Candidate(id, score));
            while (heap.size() > capacity) {
                heap.poll();
            }
        }

        double peekScore() {
            return heap.peek().score;
        }

        Candidate poll() {
            return heap.poll();
        }

        boolean isEmpty() {
            return heap.isEmpty();
        }

        int size() {
            return heap.size();
        }
    }
}
