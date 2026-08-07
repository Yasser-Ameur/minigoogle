package com.minigoogle.ml.click;

import com.minigoogle.ml.features.QueryDocumentFeatures;

/**
 * Supplies the feature vectors used for click-based training of the ranking
 * model.
 *
 * <p>Standalone nodes resolve features from the corpus held by a
 * {@link com.minigoogle.ml.features.FeatureExtractor}; a coordinator resolves
 * them from the served-impression log, which stores the exact vectors that
 * were served. Both satisfy the "train-time features equal serve-time
 * features" invariant through the same interface.</p>
 */
public interface ClickFeatureProvider {

    /**
     * Returns the normalized feature vector for the given query and document,
     * trained at position 0 so the model learns content preference rather than
     * rank bias.
     */
    QueryDocumentFeatures features(String query, int documentId);
}
