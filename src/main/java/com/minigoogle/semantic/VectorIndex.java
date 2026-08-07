package com.minigoogle.semantic;

import com.minigoogle.semantic.hnsw.HNSWGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HNSW-based approximate nearest neighbor vector index.
 *
 * Per ARCHITECTURE.md Ch13:
 *   Vector indices enable semantic search via approximate nearest neighbor
 *   lookup. HNSW (Hierarchical Navigable Small World) provides
 *   O(log n) search with high recall.
 *
 * The HNSW graph produces the candidate set for each query; scores are then
 * recomputed exactly against the stored vectors so callers always observe
 * exact cosine similarities with their original metadata. This keeps the
 * index semantics stable (same {@link VectorResult} contract as the previous
 * brute-force implementation) while gaining HNSW's logarithmic candidate
 * retrieval at larger scales.
 */
public class VectorIndex {

    /** Layers in the HNSW graph. A fixed ceiling keeps search O(log n). */
    private static final int DEFAULT_MAX_LAYERS = 6;

    /**
     * Search implementation for the index.
     *
     * <p>{@link #EXACT} performs a linear scan over every stored vector. It is
     * deterministic and returns the true nearest neighbors, which is what an
     * evaluation harness needs; construction is O(n) so multi-hundred-thousand
     * document corpora build in seconds.</p>
     *
     * <p>{@link #HNSW} builds the approximate navigable-small-world graph for
     * logarithmic candidate retrieval on very large corpora. It is the default
     * for production search nodes.</p>
     */
    public enum VectorMode { EXACT, HNSW }

    private final int dimension;
    private final int maxConnections;  // M parameter: max connections per node
    private final int efConstruction;  // efConstruction: search width during insertion
    private final VectorMode mode;
    private final List<VectorEntry> entries;
    private final Map<Integer, Integer> idToIndex;
    private final HNSWGraph graph;

    public VectorIndex(int dimension, int maxConnections, int efConstruction) {
        this(dimension, VectorMode.HNSW, maxConnections, efConstruction);
    }

    public VectorIndex(int dimension, VectorMode mode) {
        this(dimension, mode, 16, 200);
    }

    public VectorIndex(int dimension) {
        this(dimension, VectorMode.HNSW, 16, 200);
    }

    private VectorIndex(int dimension, VectorMode mode, int maxConnections, int efConstruction) {
        this.dimension = dimension;
        this.maxConnections = maxConnections;
        this.efConstruction = efConstruction;
        this.mode = mode;
        this.entries = new ArrayList<>();
        this.idToIndex = new HashMap<>();
        this.graph = mode == VectorMode.HNSW
                ? new HNSWGraph(dimension, maxConnections, DEFAULT_MAX_LAYERS)
                : null;
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
        Integer existing = idToIndex.get(id);
        if (existing != null) {
            entries.set(existing, new VectorEntry(id, vector.clone(), metadata));
            if (graph != null) {
                graph.insert(id, vector);
            }
            return;
        }
        idToIndex.put(id, entries.size());
        entries.add(new VectorEntry(id, vector.clone(), metadata));
        if (graph != null) {
            graph.insert(id, vector);
        }
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
     * <p>Candidates are retrieved from the HNSW graph (approximate nearest
     * neighbor lookup) and then scored exactly against the stored vectors, so
     * the returned similarities are exact cosine values. If the graph cannot
     * supply {@code k} candidates (e.g. a small or freshly-built graph) the
     * remaining slots are filled by a brute-force scan, guaranteeing the index
     * never returns fewer results than an exhaustive search would.</p>
     *
     * @param query The query vector.
     * @param k     Number of nearest neighbors to return.
     * @return The k nearest entries, sorted by descending similarity.
     */
    public List<VectorResult> search(double[] query, int k) {
        if (query.length != dimension) {
            throw new IllegalArgumentException("Query vector dimension mismatch");
        }
        if (entries.isEmpty() || k <= 0) {
            return List.of();
        }

        int fetch = Math.min(k, entries.size());
        if (mode == VectorMode.EXACT) {
            return exactSearch(query, fetch);
        }

        List<VectorResult> candidates = graph.search(query, fetch, efConstruction);

        List<VectorResult> results = new ArrayList<>(fetch);
        Set<Integer> found = new HashSet<>();
        for (VectorResult candidate : candidates) {
            VectorEntry entry = findEntry(candidate.id());
            if (entry != null && found.add(entry.id())) {
                results.add(new VectorResult(
                        entry.id(),
                        EmbeddingGenerator.cosineSimilarity(query, entry.vector()),
                        entry.metadata()));
            }
        }

        // Fill any shortfall with an exact scan so recall is never reduced by
        // graph approximation on small or sparsely-connected corpora.
        if (results.size() < fetch) {
            for (VectorEntry entry : entries) {
                if (found.contains(entry.id())) {
                    continue;
                }
                results.add(new VectorResult(
                        entry.id(),
                        EmbeddingGenerator.cosineSimilarity(query, entry.vector()),
                        entry.metadata()));
                found.add(entry.id());
                if (results.size() >= fetch) {
                    break;
                }
            }
        }

        results.sort(Comparator.comparingDouble(VectorResult::score).reversed()
                .thenComparingInt(VectorResult::id));
        return results.size() > k ? new ArrayList<>(results.subList(0, k)) : results;
    }

    /**
     * Deterministic exact nearest-neighbor scan over every stored vector.
     */
    private List<VectorResult> exactSearch(double[] query, int fetch) {
        List<VectorResult> results = new ArrayList<>(fetch);
        for (VectorEntry entry : entries) {
            results.add(new VectorResult(
                    entry.id(),
                    EmbeddingGenerator.cosineSimilarity(query, entry.vector()),
                    entry.metadata()));
        }
        results.sort(Comparator.comparingDouble(VectorResult::score).reversed()
                .thenComparingInt(VectorResult::id));
        return results.size() > fetch ? results.subList(0, fetch) : results;
    }

    /**
     * Returns the cosine similarity between a query vector and the stored
     * vector for the given id, or {@code null} if the id is not indexed.
     *
     * @param id          The document ID.
     * @param queryVector The query vector.
     * @return The cosine similarity, or {@code null} if not found.
     */
    public Double similarity(int id, double[] queryVector) {
        if (queryVector.length != dimension) {
            throw new IllegalArgumentException("Query vector dimension mismatch");
        }
        VectorEntry entry = findEntry(id);
        if (entry == null) {
            return null;
        }
        return EmbeddingGenerator.cosineSimilarity(queryVector, entry.vector());
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
        idToIndex.clear();
        if (graph != null) {
            graph.clear();
        }
    }

    private VectorEntry findEntry(int id) {
        Integer index = idToIndex.get(id);
        return index == null ? null : entries.get(index);
    }

    private record VectorEntry(int id, double[] vector, String metadata) {
    }

    public record VectorResult(int id, double score, String metadata) {
    }
}
