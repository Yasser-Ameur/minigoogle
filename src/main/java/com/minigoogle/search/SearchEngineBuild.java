package com.minigoogle.search;

import com.minigoogle.ml.features.FeatureExtractor;
import com.minigoogle.query.planner.QueryPlanner;
import com.minigoogle.ranking.pipeline.RankingPipeline;
import com.minigoogle.semantic.autocomplete.TrieAutocomplete;
import com.minigoogle.semantic.spell.SpellCorrector;
import com.minigoogle.storage.metadata.Metadata;
import com.minigoogle.storage.mmap.MemoryMappedIndex;

import java.util.Map;

/**
 * Everything produced by a single {@link SearchEngineBuilder#build} call: the
 * shared engine plus the collateral structures the composition root needs for
 * the suggest / stats / click endpoints and for learning-to-rank wiring.
 */
public record SearchEngineBuild(
        SearchEngine engine,
        MemoryMappedIndex mmapIndex,
        Metadata metadata,
        TrieAutocomplete autocomplete,
        SpellCorrector spellCorrector,
        RankingPipeline ranking,
        QueryPlanner planner,
        FeatureExtractor featureExtractor,
        Map<String, Integer> urlToDocId,
        Map<Integer, String> docUrls,
        Map<Integer, String> docTitles,
        Map<Integer, String> docBodies,
        Map<Integer, Integer> docLengths,
        Map<Integer, Double> pageRankScores) {
}
