package com.minigoogle.performance.vector;

/**
 * Utility class for computing similarity scores between dense vectors.
 *
 * <p>Provides dot-product and cosine similarity operations optimized
 * for scoring document vectors against query vectors. Methods are
 * structured for easy JIT auto-vectorization and SIMD intrinsic
 * recognition by the JVM.</p>
 */
public final class VectorScorer {

    private VectorScorer() {
    }

    /**
     * Computes the dot product of two vectors of equal length.
     *
     * @param a The first vector.
     * @param b The second vector, must have the same length as {@code a}.
     * @return The dot product.
     * @throws IllegalArgumentException If the vectors differ in length.
     */
    public static double dotProduct(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Vector lengths differ: " + a.length + " vs " + b.length);
        }
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
     * Computes the cosine similarity between two vectors of equal length.
     *
     * @param a The first vector.
     * @param b The second vector, must have the same length as {@code a}.
     * @return The cosine similarity, in the range [-1, 1] for normalized inputs,
     *         or [0, 1] for non-negative vectors. Returns 0.0 if either vector
     *         has zero magnitude.
     * @throws IllegalArgumentException If the vectors differ in length.
     */
    public static double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Vector lengths differ: " + a.length + " vs " + b.length);
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator == 0.0) {
            return 0.0;
        }
        return dot / denominator;
    }

    /**
     * Scores all index vectors against a single query vector using dot product.
     *
     * @param indexVectors An array of document vectors to score.
     * @param queryVector  The query vector.
     * @return A double array of scores, one per index vector, in the same order.
     * @throws IllegalArgumentException If any index vector differs in length from the query.
     */
    public static double[] batchScore(double[][] indexVectors, double[] queryVector) {
        double[] scores = new double[indexVectors.length];
        for (int i = 0; i < indexVectors.length; i++) {
            scores[i] = dotProduct(indexVectors[i], queryVector);
        }
        return scores;
    }
}
