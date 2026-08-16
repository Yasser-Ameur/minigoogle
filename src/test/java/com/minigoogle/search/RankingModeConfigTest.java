package com.minigoogle.search;

import com.minigoogle.core.config.Configuration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The mode switch: what it defaults to, and how it fails. */
class RankingModeConfigTest {

    @Test
    void defaultsToBm25WhenUnset() {
        SearchEngineConfig config = SearchEngineConfig.from(new Configuration(Map.of()));

        assertEquals(RankingMode.BM25, config.rankingMode(),
                "the default ranking must stay lexical until a corpus is measured");
        assertEquals(60, config.fusionK());
        assertEquals(1000, config.semanticDepth());
    }

    @Test
    void parsesEachModeCaseInsensitively() {
        assertEquals(RankingMode.BM25, RankingMode.from("bm25"));
        assertEquals(RankingMode.SEMANTIC, RankingMode.from("Semantic"));
        assertEquals(RankingMode.RRF, RankingMode.from("RRF"));
        assertEquals(RankingMode.RRF, RankingMode.from("  rrf  "));
    }

    @Test
    void blankOrMissingIsBm25() {
        assertEquals(RankingMode.BM25, RankingMode.from(null));
        assertEquals(RankingMode.BM25, RankingMode.from(""));
        assertEquals(RankingMode.BM25, RankingMode.from("   "));
    }

    @Test
    void rejectsAnUnknownModeRatherThanFallingBack() {
        // A typo that silently answers with a different ranking than configured
        // is a quality regression with no visible cause.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RankingMode.from("hybrid"));
        assertEquals(true, e.getMessage().contains("hybrid"));
    }

    @Test
    void readsFusionTunablesFromConfiguration() {
        SearchEngineConfig config = SearchEngineConfig.from(new Configuration(Map.of(
                "ranking.mode", "rrf",
                "ranking.rrf.k", "10",
                "ranking.semantic.depth", "500")));

        assertEquals(RankingMode.RRF, config.rankingMode());
        assertEquals(10, config.fusionK());
        assertEquals(500, config.semanticDepth());
    }

    @Test
    void fusionDepthDefaultsDeepAndIgnoresTopK() {
        // The bug this setting exists to remove: a small page size must not
        // silently narrow how much of each channel reaches fusion.
        SearchEngineConfig config = SearchEngineConfig.from(new Configuration(Map.of(
                "ranking.mode", "rrf",
                "ranking.topK", "20")));

        assertEquals(20, config.rankingTopK());
        assertEquals(SearchEngineConfig.DEFAULT_FUSION_DEPTH, config.fusionDepth());
        assertEquals(SearchEngineConfig.DEFAULT_FUSION_DEPTH, config.semanticDepth());
    }

    @Test
    void fusionDepthSetsBothChannelsAndSemanticDepthOverridesItsOwn() {
        SearchEngineConfig both = SearchEngineConfig.from(new Configuration(Map.of(
                "ranking.fusion.depth", "500")));
        assertEquals(500, both.fusionDepth());
        assertEquals(500, both.semanticDepth(), "fusion.depth must set every channel");

        SearchEngineConfig override = SearchEngineConfig.from(new Configuration(Map.of(
                "ranking.fusion.depth", "500",
                "ranking.semantic.depth", "100")));
        assertEquals(500, override.fusionDepth());
        assertEquals(100, override.semanticDepth(),
                "an explicit semantic.depth must override it for that channel only");
    }

    @Test
    void theDocumentedProductionContractIsValid() {
        // Exactly the configuration the deployment documentation prescribes.
        SearchEngineConfig config = SearchEngineConfig.from(new Configuration(Map.of(
                "ranking.mode", "bm25",
                "ranking.topK", "20",
                "ranking.fusion.depth", "1000",
                "ranking.semantic.depth", "1000",
                "ranking.rrf.k", "60")));

        assertEquals(RankingMode.BM25, config.rankingMode());
        assertEquals(20, config.rankingTopK());
        assertEquals(1000, config.fusionDepth());
        assertEquals(1000, config.semanticDepth());
        assertEquals(60, config.fusionK());
    }

    @Test
    void rejectsANonPositiveDepthRatherThanFusingAnEmptyChannel() {
        assertThrows(IllegalArgumentException.class, () -> SearchEngineConfig.from(
                new Configuration(Map.of("ranking.fusion.depth", "0"))));
        assertThrows(IllegalArgumentException.class, () -> SearchEngineConfig.from(
                new Configuration(Map.of("ranking.fusion.depth", "-1"))));
        assertThrows(IllegalArgumentException.class, () -> SearchEngineConfig.from(
                new Configuration(Map.of("ranking.semantic.depth", "0"))));
    }
}
