package com.minigoogle.semantic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the HNSW-backed {@link VectorIndex} returns the same exact-score
 * results as a brute-force scan while preserving metadata and determinism.
 */
class HnswVectorIndexTest {

    private static final int DIMENSION = 32;
    private static final int N = 60;

    private VectorIndex buildIndex(int seed) {
        Random rng = new Random(seed);
        VectorIndex index = new VectorIndex(DIMENSION);
        for (int i = 1; i <= N; i++) {
            double[] vector = new double[DIMENSION];
            for (int d = 0; d < DIMENSION; d++) {
                vector[d] = rng.nextDouble() * 2 - 1;
            }
            index.add(i, vector, "doc" + i);
        }
        return index;
    }

    private List<VectorIndex.VectorResult> bruteForce(List<double[]> stored, double[] query, int k) {
        List<VectorIndex.VectorResult> results = new ArrayList<>();
        for (int i = 0; i < stored.size(); i++) {
            double score = EmbeddingGenerator.cosineSimilarity(query, stored.get(i));
            results.add(new VectorIndex.VectorResult(i + 1, score, "doc" + (i + 1)));
        }
        results.sort(Comparator.comparingDouble(VectorIndex.VectorResult::score).reversed()
                .thenComparingInt(VectorIndex.VectorResult::id));
        return results.subList(0, Math.min(k, results.size()));
    }

    private List<double[]> storedVectors(Random rng) {
        List<double[]> stored = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            double[] vector = new double[DIMENSION];
            for (int d = 0; d < DIMENSION; d++) {
                vector[d] = rng.nextDouble() * 2 - 1;
            }
            stored.add(vector);
        }
        return stored;
    }

    @Test
    void testHnswSearchMatchesBruteForceTopResults() {
        Random rng = new Random(11);
        List<double[]> stored = storedVectors(rng);
        VectorIndex index = new VectorIndex(DIMENSION);
        for (int i = 1; i <= N; i++) {
            index.add(i, stored.get(i - 1), "doc" + i);
        }

        for (int q = 0; q < 5; q++) {
            double[] query = new double[DIMENSION];
            for (int d = 0; d < DIMENSION; d++) {
                query[d] = rng.nextDouble() * 2 - 1;
            }
            List<VectorIndex.VectorResult> exact = bruteForce(stored, query, 5);
            List<VectorIndex.VectorResult> hnsw = index.search(query, 5);

            assertEquals(5, hnsw.size(), "HNSW should return k results");
            assertEquals(exact.get(0).id(), hnsw.get(0).id(),
                    "HNSW should surface the exact nearest neighbor first");
            assertTrue(hnsw.stream().anyMatch(r -> r.id() == exact.get(0).id()));

            for (int i = 1; i < hnsw.size(); i++) {
                assertTrue(hnsw.get(i - 1).score() >= hnsw.get(i).score(),
                        "Results must be sorted by descending score");
            }
            // Scores must be exact cosine similarities.
            assertEquals(exact.get(0).score(), hnsw.get(0).score(), 1e-9);
        }
    }

    @Test
    void testSearchIsDeterministic() {
        Random rng = new Random(13);
        List<double[]> stored = storedVectors(rng);
        VectorIndex index = new VectorIndex(DIMENSION);
        for (int i = 1; i <= N; i++) {
            index.add(i, stored.get(i - 1), "doc" + i);
        }
        double[] query = new double[DIMENSION];
        for (int d = 0; d < DIMENSION; d++) {
            query[d] = rng.nextDouble() * 2 - 1;
        }
        List<VectorIndex.VectorResult> first = index.search(query, 5);
        List<VectorIndex.VectorResult> second = index.search(query, 5);
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).id(), second.get(i).id());
            assertEquals(first.get(i).score(), second.get(i).score(), 1e-12);
        }
    }

    @Test
    void testMetadataPreserved() {
        VectorIndex index = new VectorIndex(DIMENSION);
        index.add(1, new double[DIMENSION], "first doc");
        List<VectorIndex.VectorResult> results = index.search(new double[DIMENSION], 5);
        assertEquals(1, results.size());
        assertEquals("first doc", results.get(0).metadata());
        assertEquals(1, results.get(0).id());
    }

    @Test
    void testEmptyAndClearedIndex() {
        VectorIndex index = new VectorIndex(DIMENSION);
        assertTrue(index.search(new double[DIMENSION], 5).isEmpty());

        index.add(1, new double[DIMENSION], "doc1");
        index.add(2, new double[DIMENSION], "doc2");
        assertEquals(2, index.size());
        index.clear();
        assertEquals(0, index.size());
        assertTrue(index.search(new double[DIMENSION], 5).isEmpty());
    }

    @Test
    void testDimensionMismatchThrows() {
        VectorIndex index = new VectorIndex(DIMENSION);
        assertThrows(IllegalArgumentException.class, () -> index.add(1, new double[16], "bad"));
        index.add(1, new double[DIMENSION], "ok");
        assertThrows(IllegalArgumentException.class, () -> index.search(new double[16], 2));
        assertThrows(IllegalArgumentException.class, () -> index.similarity(1, new double[16]));
    }

    @Test
    void testSimilarityAndAddReplace() {
        VectorIndex index = new VectorIndex(4);
        double[] a = {1.0, 0.0, 0.0, 0.0};
        double[] b = {0.0, 1.0, 0.0, 0.0};
        double[] c = {0.0, 0.0, 1.0, 0.0};
        index.add(1, a, "orig");
        index.add(2, c, "other");

        assertNull(index.similarity(99, a));
        assertEquals(1.0, index.similarity(1, a), 1e-9);

        // Re-adding an id replaces its vector and metadata.
        index.add(1, b, "replaced");
        assertEquals(0.0, index.similarity(1, a), 1e-9);
        assertEquals(1.0, index.similarity(1, b), 1e-9);

        List<VectorIndex.VectorResult> results = index.search(b, 5);
        assertEquals(1, results.get(0).id(), "id 1 now embeds vector b");
        assertEquals("replaced", results.get(0).metadata());
        assertEquals(1.0, results.get(0).score(), 1e-9);
    }

    @Test
    void testSmallGraphExactTopK() {
        // The classic SemanticTest scenario: 3 vectors, must return the exact top-2.
        VectorIndex index = new VectorIndex(4);
        index.add(1, new double[]{1.0, 0.0, 0.0, 0.0});
        index.add(2, new double[]{0.0, 1.0, 0.0, 0.0});
        index.add(3, new double[]{0.9, 0.1, 0.0, 0.0});

        List<VectorIndex.VectorResult> results = index.search(new double[]{1.0, 0.0, 0.0, 0.0}, 2);
        assertEquals(2, results.size());
        assertEquals(1, results.get(0).id());
        assertTrue(results.get(0).score() > results.get(1).score());
        assertArrayEquals(new int[]{1, 3}, results.stream().mapToInt(VectorIndex.VectorResult::id).toArray());
    }

    @Test
    void testVectorIndexThroughEmbeddingGenerator() {
        EmbeddingGenerator generator = new EmbeddingGenerator(DIMENSION);
        VectorIndex index = new VectorIndex(DIMENSION);
        String[] docs = {
                "java programming language guide",
                "python scripting language",
                "distributed systems consensus raft",
                "web crawler robots politeness",
                "machine learning neural networks"
        };
        for (int i = 0; i < docs.length; i++) {
            index.add(i + 1, generator.embed(docs[i]), docs[i]);
        }
        List<VectorIndex.VectorResult> results = index.search(generator.embed("java programming"), 3);
        assertEquals(3, results.size());
        assertEquals(1, results.get(0).id(), "The Java doc should rank first for a Java query");
    }
}
