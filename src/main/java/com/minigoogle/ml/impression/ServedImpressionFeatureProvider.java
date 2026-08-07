package com.minigoogle.ml.impression;

import com.minigoogle.ml.click.ClickFeatureProvider;
import com.minigoogle.ml.features.FeatureNormalizer;
import com.minigoogle.ml.features.QueryDocumentFeatures;
import com.minigoogle.ml.features.RawFeatures;

/**
 * Resolves click-training features for a coordinator from the exact vectors
 * that were served.
 *
 * <p>A standalone node re-extracts features from its corpus; a coordinator has
 * no corpus, so it resolves the served vectors from {@link ImpressionLog}
 * instead. Features are normalized against the same global context the
 * impression was ranked with, at position 0, so the model learns content
 * preference rather than rank bias — identical to the standalone path.
 *
 * <p>Returns {@code null} for queries with no recorded impression, documents
 * that were never served, or candidates that carried no raw features (the
 * feature-less shard fallback). {@code ClickFeedbackTrainer} skips pairs whose
 * features cannot be resolved.</p>
 */
public class ServedImpressionFeatureProvider implements ClickFeatureProvider {

    private final ImpressionLog impressionLog;

    public ServedImpressionFeatureProvider(ImpressionLog impressionLog) {
        this.impressionLog = impressionLog;
    }

    @Override
    public QueryDocumentFeatures features(String query, int documentId) {
        ServedImpression impression = impressionLog.impression(query);
        if (impression == null || impression.results() == null) {
            return null;
        }
        for (ServedResult result : impression.results()) {
            if (result.docId() == documentId) {
                if (result.rawFeatures() == null) {
                    return null;
                }
                return FeatureNormalizer.normalize(
                        RawFeatures.of(result.rawFeatures()), impression.context(), 0);
            }
        }
        return null;
    }
}
