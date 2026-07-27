package com.minigoogle.ranking.fusion;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
     * Fuses normalized BM25 and PageRank scores for each candidate document.
     *
     * @param normalizedBm25     Map of docId → normalized BM25 score.
     * @param normalizedPageRank Map of docId → normalized PageRank score.
     * @return Map of docId → fused final score.
     */
    public Map<Integer, Double> fuse(Map<Integer, Double> normalizedBm25,
                                     Map<Integer, Double> normalizedPageRank) {
        Map<Integer, Double> fused = new HashMap<>();

        // Union of all document IDs from both score maps
        Set<Integer> allDocs = new java.util.HashSet<>(normalizedBm25.keySet());
        allDocs.addAll(normalizedPageRank.keySet());

        for (int docId : allDocs) {
            double bm25 = normalizedBm25.getOrDefault(docId, 0.0);
            double pageRank = normalizedPageRank.getOrDefault(docId, 0.0);
            fused.put(docId, bm25Weight * bm25 + pageRankWeight * pageRank);
        }

        return fused;
    }
}
