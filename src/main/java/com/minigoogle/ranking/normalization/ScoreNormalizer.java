package com.minigoogle.ranking.normalization;

import java.util.Arrays;

/**
 * Normalizes scores to the [0, 1] range using Min-Max normalization.
 *
 * This is essential because BM25 and PageRank produce scores on
 * vastly different scales (e.g. BM25 ~13.2 vs PageRank ~0.004).
 * Normalization makes weighted combination meaningful.
 */
public class ScoreNormalizer {

    /**
     * Min-Max normalizes the first {@code count} entries of {@code scores} in
     * place. If all scores are identical, all normalized values are set to 0.5.
     *
     * @param scores Dense array of raw scores, modified in place.
     * @param count  Number of leading entries to normalize.
     */
    public void normalizeInPlace(double[] scores, int count) {
        if (scores == null || count <= 0) {
            return;
        }

        double min = Double.MAX_VALUE;
        // -Double.MAX_VALUE, not Double.MIN_VALUE: MIN_VALUE is the smallest
        // positive double, so an all-zero signal would never update max and
        // would fall through the identical-scores branch below.
        double max = -Double.MAX_VALUE;

        for (int i = 0; i < count; i++) {
            if (scores[i] < min) min = scores[i];
            if (scores[i] > max) max = scores[i];
        }

        double range = max - min;

        if (range == 0.0) {
            // All scores identical — assign midpoint
            Arrays.fill(scores, 0, count, 0.5);
        } else {
            for (int i = 0; i < count; i++) {
                scores[i] = (scores[i] - min) / range;
            }
        }
    }
}
