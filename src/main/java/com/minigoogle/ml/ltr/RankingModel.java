package com.minigoogle.ml.ltr;

import com.minigoogle.ml.features.QueryDocumentFeatures;

/**
 * A ranking model that scores a query-document feature vector.
 *
 * <p>Higher scores indicate higher predicted relevance. Implementations must
 * be deterministic for a given feature vector so ranking is reproducible.</p>
 */
public interface RankingModel {

    /**
     * Scores a query-document feature vector.
     *
     * @param features The extracted features.
     * @return A relevance score (higher is better).
     */
    double score(QueryDocumentFeatures features);
}
