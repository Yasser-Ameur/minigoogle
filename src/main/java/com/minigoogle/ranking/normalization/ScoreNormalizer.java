package com.minigoogle.ranking.normalization;

import java.util.HashMap;
import java.util.Map;

/**
 * Normalizes scores to the [0, 1] range using Min-Max normalization.
 *
 * This is essential because BM25 and PageRank produce scores on
 * vastly different scales (e.g. BM25 ~13.2 vs PageRank ~0.004).
 * Normalization makes weighted combination meaningful.
 */
public class ScoreNormalizer {

    /**
     * Normalizes a map of scores to [0, 1] using Min-Max normalization.
     * If all scores are identical, all normalized values are set to 0.5.
     *
     * @param scores Map of docId → raw score.
     * @return Map of docId → normalized score in [0, 1].
     */
    public Map<Integer, Double> normalize(Map<Integer, Double> scores) {
        if (scores == null || scores.isEmpty()) {
            return Map.of();
        }

        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;

        for (double score : scores.values()) {
            if (score < min) min = score;
            if (score > max) max = score;
        }

        double range = max - min;
        Map<Integer, Double> normalized = new HashMap<>();

        if (range == 0.0) {
            // All scores identical — assign midpoint
            for (int docId : scores.keySet()) {
                normalized.put(docId, 0.5);
            }
        } else {
            for (Map.Entry<Integer, Double> entry : scores.entrySet()) {
                normalized.put(entry.getKey(), (entry.getValue() - min) / range);
            }
        }

        return normalized;
    }
}
