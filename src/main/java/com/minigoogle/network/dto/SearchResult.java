package com.minigoogle.network.dto;

import com.minigoogle.ml.features.RawFeatures;

/**
 * Standard representation of a single search result.
 *
 * <p>{@code features} holds the raw (document-local) features in
 * {@code FeatureName} order when the responding node is a shard in a
 * distributed cluster. It is {@code null} for standalone nodes and for
 * feature-less responses, and is omitted from the wire format by the
 * serializer. The coordinator uses it to perform global ranking.</p>
 */
public record SearchResult(
        String url,
        String title,
        String snippet,
        double score,
        double bm25Score,
        double pageRankScore,
        double[] features
) {
    public SearchResult(String url, String title, String snippet,
                        double score, double bm25Score, double pageRankScore) {
        this(url, title, snippet, score, bm25Score, pageRankScore, null);
    }

    public SearchResult {
        if (features != null) {
            features = features.clone();
        }
    }

    @Override
    public double[] features() {
        return features != null ? features.clone() : null;
    }

    /**
     * @return The raw features as a typed value, or {@code null} if this
     *         result carries none.
     */
    public RawFeatures rawFeatures() {
        return features != null ? RawFeatures.of(features) : null;
    }
}
