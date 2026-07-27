package com.minigoogle.semantic;

import java.util.Random;

/**
 * Generates dense vector embeddings for documents and queries.
 *
 * Per ARCHITECTURE.md Ch13:
 *   Embeddings capture semantic meaning beyond keyword matching.
 *   Each document and query is mapped to a fixed-dimensional vector
 *   in a shared embedding space.
 *
 * In production, this would use a trained model (e.g. sentence-transformers).
 * This implementation provides a simulated embedding for architecture validation.
 */
public class EmbeddingGenerator {

    private final int dimension;
    private final Random random;

    public EmbeddingGenerator(int dimension) {
        this.dimension = dimension;
        this.random = new Random(42); // Fixed seed for reproducibility
    }

    public EmbeddingGenerator() {
        this(128);
    }

    /**
     * Generates an embedding vector for a text string.
     *
     * @param text The input text.
     * @return A dense vector of the configured dimension.
     */
    public double[] embed(String text) {
        if (text == null || text.isEmpty()) {
            return new double[dimension];
        }
        // Deterministic: use text hash as seed for reproducible embeddings
        Random textRandom = new Random(text.hashCode());
        double[] vector = new double[dimension];
        double norm = 0;
        for (int i = 0; i < dimension; i++) {
            vector[i] = textRandom.nextGaussian();
            norm += vector[i] * vector[i];
        }
        // L2 normalize
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    /**
     * Generates embeddings for multiple text inputs.
     */
    public double[][] embedBatch(String[] texts) {
        double[][] embeddings = new double[texts.length][];
        for (int i = 0; i < texts.length; i++) {
            embeddings[i] = embed(texts[i]);
        }
        return embeddings;
    }

    /**
     * Computes cosine similarity between two vectors.
     */
    public static double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have the same dimension");
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0 ? 0.0 : dot / denominator;
    }

    /**
     * @return The embedding dimension.
     */
    public int getDimension() {
        return dimension;
    }
}
