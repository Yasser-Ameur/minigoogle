package com.minigoogle.search;

/**
 * Which ranking a query is answered with.
 *
 * <p>The three modes exist so the pure baselines stay reachable in production,
 * not only in the benchmark harness: a hybrid that cannot be compared against
 * its own components on the same code path is a hybrid nobody can debug.</p>
 */
public enum RankingMode {

    /** BM25 (+ PageRank) alone. The default. */
    BM25,

    /** Semantic similarity alone — the lexical ranking is discarded. */
    SEMANTIC,

    /** Reciprocal Rank Fusion of the lexical and semantic rankings. */
    RRF;

    /**
     * Parses a configured mode name, case-insensitively.
     *
     * @throws IllegalArgumentException on an unknown name — a typo silently
     *                                  falling back to BM25 would look like a
     *                                  quality regression with no visible cause
     */
    public static RankingMode from(String value) {
        if (value == null || value.isBlank()) {
            return BM25;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        for (RankingMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown ranking.mode '" + value
                + "'; expected one of bm25, semantic, rrf");
    }
}
