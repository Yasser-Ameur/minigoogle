package com.minigoogle.search;

import com.minigoogle.core.config.Configuration;

/**
 * Retrieval-stage tunables for the shared {@link SearchEngine}. All defaults
 * mirror the values previously hard-coded in the demo composition root, so
 * switching a node to the shared engine does not change behavior.
 */
public record SearchEngineConfig(
        boolean hybridEnabled,
        int fetchK,
        double lexicalWeight,
        int maxExpansions,
        int defaultTopK) {

    public static SearchEngineConfig from(Configuration config) {
        boolean semantic = config.getBoolean("semantic.enabled", true);
        boolean hybrid = semantic && config.getBoolean("semantic.hybrid.enabled", true);
        int fetchK = config.getInt("semantic.hybrid.fetchK", 60);
        double lexicalWeight = config.getDouble("semantic.hybrid.lexicalWeight", 0.5);
        int maxExpansions = config.getInt("semantic.expansion.maxExpansions", 4);
        int topK = config.getInt("search.topK", 20);
        return new SearchEngineConfig(hybrid, fetchK, lexicalWeight, maxExpansions, topK);
    }
}
