package com.minigoogle.ranking.model;

/**
 * Intermediate score representation used during the ranking pipeline.
 * Associates a document ID with a named score from a specific ranking signal
 * (e.g., "bm25", "pagerank", "semantic").
 *
 * @param documentId The internal document ID.
 * @param signal     The name of the ranking signal that produced this score.
 * @param value      The raw score value.
 */
public record Score(int documentId, String signal, double value) implements Comparable<Score> {

    @Override
    public int compareTo(Score other) {
        return Double.compare(other.value, this.value);
    }
}
