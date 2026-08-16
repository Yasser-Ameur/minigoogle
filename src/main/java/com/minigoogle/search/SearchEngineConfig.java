package com.minigoogle.search;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.ranking.fusion.ReciprocalRankFusion;

/**
 * Retrieval-stage tunables for the shared {@link SearchEngine}. All defaults
 * mirror the values previously hard-coded in the demo composition root, so
 * switching a node to the shared engine does not change behavior.
 *
 * <p>The ranking knobs below are the fair-evaluation seams:
 * {@code ranking.pagerank.enabled} and {@code ranking.diversify.enabled} control
 * the two post-retrieval transforms that otherwise make the BM25-only and hybrid
 * variants evaluate against different candidate pools and different orderings.</p>
 *
 * <h2>Depth is two independent concepts</h2>
 * <ul>
 *   <li>{@code ranking.fusion.depth} — how many results each retrieval channel
 *       contributes to hybrid ranking. This is a <em>ranking quality</em> knob:
 *       fusing a 20-deep lexical ranking against a 1000-deep semantic one
 *       measurably degrades the result (full-corpus TREC-COVID NDCG@10 0.5810 →
 *       0.5536, candidate recall 0.4804 → 0.3354).</li>
 *   <li>{@code ranking.topK} — how many results are returned and rendered. This
 *       is a <em>presentation</em> knob, and it is what bounds snippet
 *       generation.</li>
 * </ul>
 * They were previously the same value, which meant asking for 20 results also
 * silently narrowed fusion to 20 inputs per channel. They are now separate:
 * {@code fusion.depth=1000, topK=20} fuses deeply and renders twenty.
 */
public record SearchEngineConfig(
        boolean hybridEnabled,
        int fetchK,
        double lexicalWeight,
        int maxExpansions,
        int defaultTopK,
        int rankingTopK,
        boolean pagerankEnabled,
        boolean diversifyEnabled,
        boolean rerankEnabled,
        RankingMode rankingMode,
        int fusionK,
        int semanticDepth,
        int fusionDepth) {

    /** The lexical/semantic depth supplied to fusion when no value is configured. */
    public static final int DEFAULT_FUSION_DEPTH = 1000;

    public SearchEngineConfig {
        // A non-positive depth would hand fusion an empty channel and silently
        // reduce hybrid ranking to whichever side survived.
        if (fusionDepth <= 0) {
            throw new IllegalArgumentException(
                    "ranking.fusion.depth must be positive, got " + fusionDepth);
        }
        if (semanticDepth <= 0) {
            throw new IllegalArgumentException(
                    "ranking.semantic.depth must be positive, got " + semanticDepth);
        }
    }

    public static SearchEngineConfig from(Configuration config) {
        // See SearchEngineBuilder: the embedding is feature hashing, not a
        // trained model, and hybrid fusion measurably degrades both BEIR datasets.
        boolean semantic = config.getBoolean("semantic.enabled", false);
        boolean hybrid = semantic && config.getBoolean("semantic.hybrid.enabled", true);
        int fetchK = config.getInt("semantic.hybrid.fetchK", 60);
        double lexicalWeight = config.getDouble("semantic.hybrid.lexicalWeight", 0.5);
        int maxExpansions = config.getInt("semantic.expansion.maxExpansions", 4);
        int topK = config.getInt("search.topK", 20);
        int rankingTopK = config.getInt("ranking.topK", 20);
        boolean pagerank = config.getBoolean("ranking.pagerank.enabled", true);
        boolean diversify = config.getBoolean("ranking.diversify.enabled", true);
        // Defaults to the semantic switch: the cross-encoder only carries a real
        // signal when a vector index exists. With semantic off it replaces the
        // BM25 ordering with snippet term overlap, which is strictly worse.
        boolean rerank = config.getBoolean("ranking.rerank.enabled", semantic);
        // Stays BM25 until a corpus is measured. RRF beat BM25 on both BEIR
        // datasets tested, but it costs a 384-dimension encoder pass per query
        // and a prebuilt vector store; a deployment without those must keep
        // working, and silently defaulting to a mode whose data may be missing
        // is worse than requiring one line of configuration.
        RankingMode mode = RankingMode.from(config.get("ranking.mode", "bm25"));
        int fusionK = config.getInt("ranking.rrf.k", ReciprocalRankFusion.DEFAULT_K);
        // Deliberately NOT defaulted to ranking.topK: that coupling is the bug
        // this setting exists to remove. A deployment that sets topK=20 and says
        // nothing about fusion still fuses 1000-deep.
        int fusionDepth = config.getInt("ranking.fusion.depth", DEFAULT_FUSION_DEPTH);
        // fusion.depth sets the depth of every channel; semantic.depth overrides
        // it for the semantic channel alone, since that one has been swept
        // independently (100/500/1000) and may warrant a different value.
        int semanticDepth = config.getInt("ranking.semantic.depth", fusionDepth);
        return new SearchEngineConfig(hybrid, fetchK, lexicalWeight, maxExpansions, topK,
                rankingTopK, pagerank, diversify, rerank, mode, fusionK, semanticDepth, fusionDepth);
    }
}
