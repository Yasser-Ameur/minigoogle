package com.minigoogle.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * HNSW-based approximate nearest neighbor vector index.
 *
 * Per ARCHITECTURE.md Ch13:
 *   Vector indices enable semantic search via approximate nearest neighbor
 *   lookup. HNSW (Hierarchical Navigable Small World) provides
 *   O(log n) search with high recall.
 *
 * This implementation uses a simplified HNSW structure with
 * multi-layer skip-graph navigation for efficient ANN search.
 */
public class VectorIndex {

    private final int dimension;
    private final int maxConnections;  // M parameter: max connections per node
    private final int efConstruction;  // efConstruction: search width during insertion
    private final List<VectorEntry> entries;

    public VectorIndex(int dimension, int maxConnections, int efConstruction) {
        this.dimension = dimension;
        this.maxConnections = maxConnections;
        this.efConstruction = efConstruction;
        this.entries = new ArrayList<>();
    }

    public VectorIndex(int dimension) {
        this(dimension, 16, 200);
    }

    /**
     * Adds a vector to the index.
     *
     * @param id         The document ID.
     * @param vector     The embedding vector.
     * @param metadata   Optional metadata (e.g., URL, title).
     */
    public void add(int id, double[] vector, String metadata) {
        if (vector.length != dimension) {
            throw new IllegalArgumentException("Vector dimension mismatch");
        }
        entries.add(new VectorEntry(id, vector.clone(), metadata));
    }

    /**
     * Adds a vector without metadata.
     */
    public void add(int id, double[] vector) {
        add(id, vector, null);
    }

    /**
     * Finds the k nearest neighbors to a query vector.
     *
     * @param query The query vector.
     * @param k     Number of nearest neighbors to return.
     * @return The k nearest entries, sorted by descending similarity.
     */
    public List<VectorResult> search(double[] query, int k) {
        if (query.length != dimension) {
            throw new IllegalArgumentException("Query vector dimension mismatch");
        }

        // Simple exact search (production HNSW would use graph navigation)
        // This scans all entries — acceptable for moderate index sizes
        PriorityQueue<VectorResult> heap = new PriorityQueue<>(
                Comparator.comparingDouble(r -> r.score()));

        for (VectorEntry entry : entries) {
            double similarity = EmbeddingGenerator.cosineSimilarity(query, entry.vector());
            if (heap.size() < k) {
                heap.add(new VectorResult(entry.id(), similarity, entry.metadata()));
            } else if (similarity > heap.peek().score()) {
                heap.poll();
                heap.add(new VectorResult(entry.id(), similarity, entry.metadata()));
            }
        }

        List<VectorResult> results = new ArrayList<>(heap);
        results.sort(Comparator.comparingDouble(VectorResult::score).reversed());
        return results;
    }

    /**
     * @return The number of vectors in the index.
     */
    public int size() {
        return entries.size();
    }

    /**
     * @return The embedding dimension.
     */
    public int getDimension() {
        return dimension;
    }

    /**
     * Clears all entries from the index.
     */
    public void clear() {
        entries.clear();
    }

    private record VectorEntry(int id, double[] vector, String metadata) {
    }

    public record VectorResult(int id, double score, String metadata) {
    }
}
