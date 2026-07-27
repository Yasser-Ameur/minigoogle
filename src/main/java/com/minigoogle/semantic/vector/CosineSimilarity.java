package com.minigoogle.semantic.vector;

import com.minigoogle.semantic.embedding.DenseVector;

/**
 * Computes cosine similarity between vectors.
 *
 * <p>Cosine similarity measures the angle between two vectors in a high-dimensional
 * space, returning a value in [-1, 1] where 1 means identical direction, 0 means
 * orthogonal, and -1 means opposite direction.</p>
 *
 * <p>Zero vectors gracefully return a similarity of 0.0.</p>
 */
public final class CosineSimilarity {

    private CosineSimilarity() {
        // Utility class
    }

    /**
     * Computes cosine similarity between two raw double arrays.
     *
     * @param a The first vector.
     * @param b The second vector. Must have the same length as {@code a}.
     * @return The cosine similarity in [-1, 1], or 0.0 if either vector is zero.
     * @throws IllegalArgumentException If the vectors have different dimensions.
     */
    public static double compute(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Dimension mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0.0 ? 0.0 : dot / denominator;
    }

    /**
     * Computes cosine similarity between two {@link DenseVector} instances.
     *
     * @param a The first vector.
     * @param b The second vector. Must have the same dimension as {@code a}.
     * @return The cosine similarity in [-1, 1], or 0.0 if either vector is zero.
     * @throws IllegalArgumentException If the vectors have different dimensions.
     */
    public static double compute(DenseVector a, DenseVector b) {
        return compute(a.toArray(), b.toArray());
    }

    /**
     * Computes cosine similarity between a query vector and a batch of document vectors.
     *
     * @param vectors The document vectors to score against.
     * @param query   The query vector.
     * @return An array of similarity scores, one per document vector.
     * @throws IllegalArgumentException If any document vector has a different dimension than the query.
     */
    public static double[][] computeBatch(double[][] vectors, double[] query) {
        double[][] results = new double[vectors.length][2];
        for (int i = 0; i < vectors.length; i++) {
            results[i][0] = i;
            results[i][1] = compute(vectors[i], query);
        }
        return results;
    }
}
