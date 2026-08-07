package com.minigoogle.ranking.pipeline;

import com.minigoogle.ml.features.QueryDocumentFeatures;

/**
 * The output of {@link GlobalRankingPipeline}: a candidate paired with the
 * normalized features used to score it and its final rank position.
 *
 * @param candidate The ranked candidate.
 * @param features  The normalized feature vector used for scoring (identical
 *                  to the vector that would be served/trained on).
 * @param score     The ranking model score.
 * @param position  Zero-based final rank position.
 */
public record RankedResult(
        RankedCandidate candidate,
        QueryDocumentFeatures features,
        double score,
        int position) {
}
