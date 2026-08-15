package com.minigoogle.search;

import com.minigoogle.core.config.Configuration;

/**
 * Retrieval-stage tunables for the shared {@link SearchEngine}. All defaults
 * mirror the values previously hard-coded in the demo composition root, so
 * switching a node to the shared engine does not change behavior.
 *
 * <p>The ranking knobs below are the fair-evaluation seams: {@code ranking.topK}
 * is the candidate cutoff applied to the lexical ranking stage (not the final
 * result count), {@code ranking.pagerank.enabled} and
 * {@code ranking.diversify.enabled} control the two post-retrieval transforms
 * that otherwise make the BM25-only and hybrid variants evaluate against
 * different candidate pools and different orderings.</p>
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
        boolean rerankEnabled) {

    public static SearchEngineConfig from(Configuration config) {
        boolean semantic = config.getBoolean("semantic.enabled", true);
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
        return new SearchEngineConfig(hybrid, fetchK, lexicalWeight, maxExpansions, topK,
                rankingTopK, pagerank, diversify, rerank);
    }
}
