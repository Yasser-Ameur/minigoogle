package com.minigoogle.ml.ltr;

import com.minigoogle.ml.features.QueryDocumentFeatures;

/**
 * A pairwise preference for training: the {@code preferred} document should
 * outrank the {@code nonPreferred} document for the same query.
 */
public record TrainingPair(
        QueryDocumentFeatures preferred,
        QueryDocumentFeatures nonPreferred
) {
}
