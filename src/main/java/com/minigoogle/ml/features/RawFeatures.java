package com.minigoogle.ml.features;

/**
 * Document-local raw features for a query-document pair, before global
 * normalization.
 *
 * <p>These are the features a shard can compute for a document it owns: every
 * value is a pure function of the query and the document (plus the shared
 * embedding space), with no dependence on corpus-global statistics. Global
 * normalization ({@link NormalizationContext}) and the rank {@code POSITION}
 * feature are applied later by {@link FeatureNormalizer} at the ranking layer,
 * so the same raw features can be normalized against either local (standalone)
 * or global (coordinator) statistics.</p>
 *
 * @param bm25               Saturated term-frequency score in [0, 1].
 * @param pageRank           Raw PageRank (un-normalized).
 * @param titleMatch         Fraction of query terms present in the title, in [0, 1].
 * @param urlMatch           Fraction of query terms present in the URL, in [0, 1].
 * @param termOverlap        Fraction of distinct query terms present in the body, in [0, 1].
 * @param semanticSimilarity Clamped query-document cosine similarity in [0, 1].
 * @param docLength          Raw document length (un-normalized).
 * @param position           Unused at extraction time; 0. {@code POSITION} is
 *                           assigned at the ranking layer.
 */
public record RawFeatures(
        double bm25,
        double pageRank,
        double titleMatch,
        double urlMatch,
        double termOverlap,
        double semanticSimilarity,
        double docLength,
        double position) {

    public RawFeatures {
        position = 0.0;
    }

    /**
     * Wraps a feature vector in {@link FeatureName} order (length 8).
     *
     * @throws IllegalArgumentException if the array length is not
     *                                  {@link FeatureName#values() values().length}.
     */
    public static RawFeatures of(double[] values) {
        if (values == null || values.length != FeatureName.values().length) {
            throw new IllegalArgumentException(
                    "Raw feature vector must have length " + FeatureName.values().length);
        }
        return new RawFeatures(
                values[FeatureName.BM25.ordinal()],
                values[FeatureName.PAGE_RANK.ordinal()],
                values[FeatureName.TITLE_MATCH.ordinal()],
                values[FeatureName.URL_MATCH.ordinal()],
                values[FeatureName.TERM_OVERLAP.ordinal()],
                values[FeatureName.SEMANTIC_SIMILARITY.ordinal()],
                values[FeatureName.DOC_LENGTH.ordinal()],
                values[FeatureName.POSITION.ordinal()]);
    }

    /**
     * @return The raw features in {@link FeatureName} order.
     */
    public double[] toArray() {
        return new double[] {
            bm25, pageRank, titleMatch, urlMatch, termOverlap, semanticSimilarity, docLength, position
        };
    }

    public double get(FeatureName name) {
        return switch (name) {
            case BM25 -> bm25;
            case PAGE_RANK -> pageRank;
            case TITLE_MATCH -> titleMatch;
            case URL_MATCH -> urlMatch;
            case TERM_OVERLAP -> termOverlap;
            case SEMANTIC_SIMILARITY -> semanticSimilarity;
            case DOC_LENGTH -> docLength;
            case POSITION -> position;
        };
    }
}
