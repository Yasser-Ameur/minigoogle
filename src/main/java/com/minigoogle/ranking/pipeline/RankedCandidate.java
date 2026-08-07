package com.minigoogle.ranking.pipeline;

import com.minigoogle.ml.features.RawFeatures;

/**
 * A retrieval-stage candidate that carries its raw (document-local) features
 * so that global ranking can normalize and score it.
 *
 * <p>Produced by the retrieval stage — standalone retrieval or a distributed
 * shard — and consumed by the single shared {@link GlobalRankingPipeline}.</p>
 *
 * @param documentId   The document identity. Standalone nodes use
 *                     {@code String.valueOf(documentId)}; the coordinator uses
 *                     its stable synthetic id for a URL.
 * @param url          The document URL (global identity across shards).
 * @param title        The document title.
 * @param snippet      A highlighted snippet from the document body.
 * @param bm25Score    Raw BM25 text relevance score.
 * @param pageRankScore Raw PageRank authority score.
 * @param rawFeatures  Raw, pre-normalization features.
 */
public record RankedCandidate(
        String documentId,
        String url,
        String title,
        String snippet,
        double bm25Score,
        double pageRankScore,
        RawFeatures rawFeatures) {
}
