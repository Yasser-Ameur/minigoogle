package com.minigoogle.semantic;

import java.util.Locale;

/**
 * Generates dense vector embeddings for documents and queries.
 *
 * Per ARCHITECTURE.md Ch13:
 *   Embeddings capture semantic meaning beyond keyword matching.
 *   Each document and query is mapped to a fixed-dimensional vector
 *   in a shared embedding space.
 *
 * In production, this would use a trained model (e.g. sentence-transformers).
 * This implementation is a real, dependency-free content embedding: it uses
 * feature hashing over the tokenized text (a "hashing trick" with sign
 * hashing) so that documents sharing vocabulary land close together, then
 * L2-normalizes the result. It is fully deterministic for a given text.
 */
public class EmbeddingGenerator {

    private final int dimension;

    public EmbeddingGenerator(int dimension) {
        this.dimension = dimension;
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
        if (text == null || text.isBlank()) {
            return new double[dimension];
        }
        double[] vector = new double[dimension];
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^a-z0-9]+")) {
            if (token.isEmpty()) {
                continue;
            }
            int hash = token.hashCode();
            int bucket = (hash & Integer.MAX_VALUE) % dimension;
            double sign = ((hash >>> 16) & 1) == 0 ? 1.0 : -1.0;
            vector[bucket] += sign;
        }
        return l2Normalize(vector);
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

    private double[] l2Normalize(double[] vector) {
        double normSq = 0;
        for (double v : vector) {
            normSq += v * v;
        }
        if (normSq == 0) {
            return vector;
        }
        double norm = Math.sqrt(normSq);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
        return vector;
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
