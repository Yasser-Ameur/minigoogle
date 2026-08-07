package com.minigoogle.network;

import com.minigoogle.ml.features.FeatureName;
import com.minigoogle.ml.features.RawFeatures;
import com.minigoogle.network.dto.SearchResponse;
import com.minigoogle.network.dto.SearchResult;
import com.minigoogle.network.serialization.JsonSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Wire-contract tests for the shard -> coordinator candidate protocol:
 * results carry raw feature vectors and the response carries the shard's
 * corpus statistics for global normalization.
 */
class SearchCoordinatorWireContractTest {

    @Test
    void shardResponseCarriesRawFeaturesAndCorpusStats() {
        double[] raw = new RawFeatures(
                0.7, 1.4, 0.5, 0.0, 1.0, 0.3, 812, 0.0).toArray();
        SearchResult result = new SearchResult(
                "http://shard/doc", "Doc", "snippet", 0.9, 0.7, 0.05, raw);
        SearchResponse response = new SearchResponse(
                5, 1, List.of(result), null, 4.0, 3000);

        SearchResponse deserialized = JsonSerializer.fromJson(
                JsonSerializer.toJson(response), SearchResponse.class);

        assertEquals(4.0, deserialized.maxPageRank(), 0.001);
        assertEquals(3000, deserialized.maxDocLength(), 0.001);

        SearchResult roundTripped = deserialized.results().get(0);
        RawFeatures features = roundTripped.rawFeatures();
        assertEquals(8, roundTripped.features().length, "Raw features span FeatureName order");
        assertEquals(0.7, features.bm25(), 0.001);
        assertEquals(1.4, features.pageRank(), 0.001);
        assertEquals(0.5, features.titleMatch(), 0.001);
        assertEquals(812, features.docLength(), 0.001);
        assertEquals(FeatureName.values().length, roundTripped.features().length);
    }

    @Test
    void standaloneResponseOmitsFeaturesAndStats() {
        SearchResult result = new SearchResult(
                "http://standalone/doc", "Doc", "snippet", 0.9, 0.7, 0.05);
        SearchResponse response = new SearchResponse(3, 1, List.of(result));

        SearchResponse deserialized = JsonSerializer.fromJson(
                JsonSerializer.toJson(response), SearchResponse.class);

        assertNull(deserialized.results().get(0).features(), "Standalone results carry no features");
        assertNull(deserialized.results().get(0).rawFeatures());
        assertEquals(0.0, deserialized.maxPageRank(), 0.001);
        assertEquals(0.0, deserialized.maxDocLength(), 0.001);
    }

    @Test
    void defaultShardStatsAreZeroWhenAbsent() {
        SearchResponse response = new SearchResponse(3, 0, List.of());
        SearchResponse deserialized = JsonSerializer.fromJson(
                JsonSerializer.toJson(response), SearchResponse.class);
        assertEquals(0.0, deserialized.maxPageRank(), 0.001);
        assertEquals(0.0, deserialized.maxDocLength(), 0.001);
    }
}
