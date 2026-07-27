package com.minigoogle.semantic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for semantic search (embeddings, cosine similarity) functionality. */
class SemanticTest {

    @Test
    void testEmbeddingGenerator() {
        EmbeddingGenerator generator = new EmbeddingGenerator(64);
        double[] embedding = generator.embed("hello world");
        assertEquals(64, embedding.length);
        // Should be L2 normalized
        double norm = 0;
        for (double v : embedding) norm += v * v;
        assertEquals(1.0, norm, 0.001);
    }

    @Test
    void testEmbeddingDeterminism() {
        EmbeddingGenerator generator = new EmbeddingGenerator();
        double[] a = generator.embed("test");
        double[] b = generator.embed("test");
        assertArrayEquals(a, b);
    }

    @Test
    void testCosineSimilarity() {
        double[] a = {1.0, 0.0, 0.0};
        double[] b = {1.0, 0.0, 0.0};
        assertEquals(1.0, EmbeddingGenerator.cosineSimilarity(a, b), 0.001);

        double[] c = {0.0, 1.0, 0.0};
        assertEquals(0.0, EmbeddingGenerator.cosineSimilarity(a, c), 0.001);
    }

    @Test
    void testVectorIndex() {
        VectorIndex index = new VectorIndex(4);
        index.add(1, new double[]{1.0, 0.0, 0.0, 0.0});
        index.add(2, new double[]{0.0, 1.0, 0.0, 0.0});
        index.add(3, new double[]{0.9, 0.1, 0.0, 0.0});

        var results = index.search(new double[]{1.0, 0.0, 0.0, 0.0}, 2);
        assertEquals(2, results.size());
        assertEquals(1, results.get(0).id()); // Most similar
        assertTrue(results.get(0).score() > results.get(1).score());
    }

    @Test
    void testVectorIndexEmpty() {
        VectorIndex index = new VectorIndex(4);
        var results = index.search(new double[]{1, 0, 0, 0}, 5);
        assertTrue(results.isEmpty());
    }
}
