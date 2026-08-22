package com.minigoogle.ranking.fusion;

/**
 * Combines multiple normalized score signals into a single final score
 * using configurable weighted linear combination.
 *
 * Default: finalScore = 0.75 × BM25 + 0.25 × PageRank
 */
public class ScoreFusion {

    private final double bm25Weight;
    private final double pageRankWeight;

    public ScoreFusion(double bm25Weight, double pageRankWeight) {
        this.bm25Weight = bm25Weight;
        this.pageRankWeight = pageRankWeight;
    }

    /**
     * Creates a ScoreFusion with standard weights (0.75 BM25, 0.25 PageRank).
     */
    public ScoreFusion() {
        this(0.75, 0.25);
    }

    /**
     * Fuses one document's two normalized signals.
     *
     * @param normalizedBm25     Normalized BM25 score in [0, 1].
     * @param normalizedPageRank Normalized PageRank score in [0, 1].
     * @return The fused final score.
     */
    public double fuse(double normalizedBm25, double normalizedPageRank) {
        return bm25Weight * normalizedBm25 + pageRankWeight * normalizedPageRank;
    }
}
