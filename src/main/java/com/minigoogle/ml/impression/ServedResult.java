package com.minigoogle.ml.impression;

/**
 * One document in a served impression, as seen by the coordinator.
 *
 * <p>The coordinator serves documents that may live on any shard, addressed by
 * shard-local ids. This record therefore carries the cross-node URL identity,
 * the coordinator-global {@code docId} assigned by {@link DocIdRegistry}, and
 * the exact raw feature vector that was used for global ranking — the value
 * {@link ServedImpressionFeatureProvider} resolves at training time so
 * train-time features equal serve-time features.</p>
 */
public record ServedResult(
        int docId,
        String url,
        String title,
        String snippet,
        double score,
        double bm25Score,
        double pageRankScore,
        double[] rawFeatures) {

    public ServedResult {
        rawFeatures = rawFeatures == null ? null : rawFeatures.clone();
    }
}
