package com.minigoogle.ml.ltr;

import com.minigoogle.ml.features.FeatureName;
import com.minigoogle.ml.features.QueryDocumentFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the pairwise (RankNet-style) learning-to-rank trainer. */
class PairwiseRankerTrainerTest {

    private QueryDocumentFeatures features(String query, int docId, double bm25, double pageRank) {
        double[] values = new double[FeatureName.values().length];
        values[FeatureName.BM25.ordinal()] = bm25;
        values[FeatureName.PAGE_RANK.ordinal()] = pageRank;
        return new QueryDocumentFeatures(query, docId, values);
    }

    @Test
    void testTrainingCorrectsWrongOrdering() {
        // The default model ignores BM25 ordering here because we set PAGE_RANK
        // to dominate: initially the non-preferred doc scores higher.
        double[] weights = new double[FeatureName.values().length];
        weights[FeatureName.PAGE_RANK.ordinal()] = 1.0;
        LinearRankingModel model = new LinearRankingModel(weights);

        QueryDocumentFeatures preferred = features("q", 1, 0.9, 0.0);
        QueryDocumentFeatures nonPreferred = features("q", 2, 0.1, 1.0);

        assertTrue(model.score(preferred) < model.score(nonPreferred),
                "Setup: model must initially prefer the non-preferred doc");

        List<TrainingPair> pairs = List.of(new TrainingPair(preferred, nonPreferred));
        int used = PairwiseRankerTrainer.train(model, pairs, 100, 0.1);

        assertEquals(1, used);
        assertTrue(model.score(preferred) > model.score(nonPreferred),
                "Training should make the preferred document outrank the non-preferred one");

        // The BM25 weight should have risen and the PAGE_RANK weight dropped.
        double[] after = model.weights();
        assertTrue(after[FeatureName.BM25.ordinal()] > 0.35,
                "BM25 weight should grow from its default when it carries the signal");
        assertTrue(after[FeatureName.PAGE_RANK.ordinal()] < 1.0,
                "PAGE_RANK weight should shrink as it conflicts with the preference");
    }

    @Test
    void testEmptyPairsReturnZeroAndLeaveModelUnchanged() {
        LinearRankingModel model = new LinearRankingModel();
        double[] before = model.weights();
        int used = PairwiseRankerTrainer.train(model, List.of(), 10, 0.1);
        assertEquals(0, used);
        double[] after = model.weights();
        for (int i = 0; i < before.length; i++) {
            assertEquals(before[i], after[i], 1e-12, "Weights must be unchanged for empty input");
        }
    }

    @Test
    void testTrainingReinforcesConsistentPreferences() {
        LinearRankingModel model = new LinearRankingModel();
        QueryDocumentFeatures strong = features("q", 1, 1.0, 1.0);
        QueryDocumentFeatures weak = features("q", 2, 0.0, 0.0);

        double marginBefore = model.score(strong) - model.score(weak);
        PairwiseRankerTrainer.train(model,
                List.of(new TrainingPair(strong, weak), new TrainingPair(strong, weak)), 50, 0.05);
        double marginAfter = model.score(strong) - model.score(weak);

        assertTrue(marginAfter > marginBefore,
                "Consistent preferences should increase the winning margin");
        assertTrue(model.score(strong) > model.score(weak));
    }
}
