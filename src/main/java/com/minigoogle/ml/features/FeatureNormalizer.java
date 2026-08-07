package com.minigoogle.ml.features;

/**
 * Normalizes {@link RawFeatures} against a {@link NormalizationContext} into
 * {@link QueryDocumentFeatures}, the [0, 1] feature vector consumed by the
 * ranking model.
 *
 * <p>Document-local features (saturated BM25, title/URL/overlap fractions,
 * semantic similarity) are already bounded and pass through unchanged. Corpus
 * relative features ({@code PAGE_RANK}, {@code DOC_LENGTH}) are normalized
 * against the context maximum. {@code POSITION} is the only feature assigned
 * by the ranking layer (via the position argument), never by the shard.</p>
 *
 * <p>Standalone and distributed execution share this class, so a feature
 * vector served by the coordinator is produced by the exact same code path as
 * one served by a standalone node.</p>
 */
public final class FeatureNormalizer {

    private FeatureNormalizer() {
    }

    /**
     * @param raw      Raw features extracted on the node that owns the document.
     * @param context  Corpus-global normalization context.
     * @param position Zero-based position of the document in the ranking stage
     *                 order, used for the {@code POSITION} feature.
     * @return The normalized feature vector in {@link FeatureName} order.
     */
    public static QueryDocumentFeatures normalize(
            RawFeatures raw, NormalizationContext context, int position) {
        double[] values = new double[FeatureName.values().length];
        values[FeatureName.BM25.ordinal()] = clamp(raw.bm25());
        values[FeatureName.PAGE_RANK.ordinal()] =
                normalizeAgainstMax(raw.pageRank(), context.maxPageRank());
        values[FeatureName.TITLE_MATCH.ordinal()] = clamp(raw.titleMatch());
        values[FeatureName.URL_MATCH.ordinal()] = clamp(raw.urlMatch());
        values[FeatureName.TERM_OVERLAP.ordinal()] = clamp(raw.termOverlap());
        values[FeatureName.SEMANTIC_SIMILARITY.ordinal()] = clamp(raw.semanticSimilarity());
        values[FeatureName.DOC_LENGTH.ordinal()] = normalizeDocLength(
                raw.docLength(), context.maxDocLength());
        values[FeatureName.POSITION.ordinal()] = 1.0 / (position + 1.0);
        return QueryDocumentFeatures.of(values);
    }

    private static double normalizeAgainstMax(double value, double max) {
        if (max <= 0.0) {
            return 0.0;
        }
        return clamp(value / max);
    }

    private static double normalizeDocLength(double length, double maxLength) {
        if (maxLength <= 1.0) {
            return 0.0;
        }
        return clamp(Math.log1p(length) / Math.log1p(maxLength));
    }

    private static double clamp(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }
}
