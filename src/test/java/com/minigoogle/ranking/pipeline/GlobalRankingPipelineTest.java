package com.minigoogle.ranking.pipeline;

import com.minigoogle.ml.features.FeatureName;
import com.minigoogle.ml.features.NormalizationContext;
import com.minigoogle.ml.features.RawFeatures;
import com.minigoogle.ml.ltr.LinearRankingModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the shared global ranking pipeline. */
class GlobalRankingPipelineTest {

    private static RankedCandidate candidate(String id, String url, RawFeatures raw) {
        return new RankedCandidate(id, url, "Title " + id, "snippet", 0.5, 0.5, raw);
    }

    private static RawFeatures features(double bm25, double title, double position) {
        return new RawFeatures(bm25, 0.5, title, 0.0, bm25, 0.0, 100, position);
    }

    @Test
    void sortsCandidatesByModelScoreDescending() {
        List<RankedCandidate> candidates = List.of(
                candidate("a", "http://a", features(0.1, 0.1, 0)),
                candidate("b", "http://b", features(0.9, 0.9, 0)),
                candidate("c", "http://c", features(0.5, 0.5, 0)));

        List<RankedResult> results = GlobalRankingPipeline.rank(
                "q", candidates, new NormalizationContext(1.0, 100), new LinearRankingModel());

        assertEquals(List.of("b", "c", "a"),
                results.stream().map(r -> r.candidate().documentId()).toList());
    }

    @Test
    void assignsSequentialFinalPositions() {
        List<RankedCandidate> candidates = List.of(
                candidate("a", "http://a", features(0.1, 0.1, 0)),
                candidate("b", "http://b", features(0.9, 0.9, 0)));

        List<RankedResult> results = GlobalRankingPipeline.rank(
                "q", candidates, new NormalizationContext(1.0, 100), new LinearRankingModel());

        assertEquals(0, results.get(0).position());
        assertEquals(1, results.get(1).position());
        // POSITION feature reflects the pre-ranking candidate order, not final order:
        // "b" ranked first but was second in the candidate order.
        assertEquals(0.5, results.get(0).features().get(FeatureName.POSITION), 0.001);
        assertEquals(1.0, results.get(1).features().get(FeatureName.POSITION), 0.001);
    }

    @Test
    void featuresUsedForScoringAreExposedForImpressionLogging() {
        List<RankedCandidate> candidates = List.of(
                candidate("a", "http://a", features(0.8, 0.2, 0)));

        List<RankedResult> results = GlobalRankingPipeline.rank(
                "q", candidates, new NormalizationContext(1.0, 100), new LinearRankingModel());

        RankedResult result = results.get(0);
        assertEquals(0.8, result.features().get(FeatureName.BM25), 0.001);
        assertEquals(0.2, result.features().get(FeatureName.TITLE_MATCH), 0.001);
        // Score must equal the model's score of the exposed features.
        assertEquals(new LinearRankingModel().score(result.features()), result.score(), 1e-12);
    }

    @Test
    void emptyAndNullInputsYieldEmptyOutput() {
        assertTrue(GlobalRankingPipeline.rank("q", List.of(),
                new NormalizationContext(1.0, 100), new LinearRankingModel()).isEmpty());
        assertTrue(GlobalRankingPipeline.rank("q", null,
                new NormalizationContext(1.0, 100), new LinearRankingModel()).isEmpty());
    }
}
