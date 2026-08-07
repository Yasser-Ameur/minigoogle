package com.minigoogle.ml.click;

import com.minigoogle.ml.features.FeatureExtractor;
import com.minigoogle.ml.features.QueryDocumentFeatures;
import com.minigoogle.ml.ltr.LinearRankingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that click feedback refines the learning-to-rank model.
 */
class ClickFeedbackTrainerTest {

    private FeatureExtractor extractor;
    private LinearRankingModel model;
    private ClickTracker tracker;
    private ClickFeedbackTrainer trainer;

    @BeforeEach
    void setup() {
        Map<Integer, String> urls = new HashMap<>();
        urls.put(1, "http://example.com/alpha");
        urls.put(2, "http://example.com/alpha-two");
        Map<Integer, String> titles = new HashMap<>();
        titles.put(1, "Alpha");
        titles.put(2, "Alpha Two");
        Map<Integer, String> bodies = new HashMap<>();
        bodies.put(1, "alpha alpha");
        bodies.put(2, "alpha alpha alpha");
        Map<Integer, Integer> lengths = new HashMap<>();
        lengths.put(1, 100);
        lengths.put(2, 100);
        Map<Integer, Double> pageRanks = new HashMap<>();
        pageRanks.put(1, 0.5);
        pageRanks.put(2, 0.5);

        extractor = new FeatureExtractor(urls, titles, bodies, lengths, pageRanks, null, null);
        model = new LinearRankingModel();
        tracker = new ClickTracker();
        trainer = new ClickFeedbackTrainer(extractor, model, tracker, 1, 50, 0.1);
    }

    @Test
    void testClickTriggersTrainingAndReturnsPairCount() {
        tracker.recordImpression("alpha", List.of(1, 2));

        int pairs = trainer.onClick(new ClickEvent("alpha", 2, "http://example.com/alpha-two", 2));

        assertEquals(1, pairs, "Clicking position 2 over position 1 yields one preference pair");
        assertEquals(1, tracker.clickCount());

        double[] weights = model.weights();
        double[] defaults = new LinearRankingModel().weights();
        boolean changed = false;
        for (int i = 0; i < weights.length; i++) {
            if (Math.abs(weights[i] - defaults[i]) > 1e-9) {
                changed = true;
                break;
            }
        }
        assertTrue(changed, "The model weights must change after click-based training");
    }

    @Test
    void testFeedbackWidensWinningMargin() {
        tracker.recordImpression("alpha", List.of(1, 2));

        double before = model.score(features("alpha", 2)) - model.score(features("alpha", 1));
        trainer.onClick(new ClickEvent("alpha", 2, "http://example.com/alpha-two", 2));
        double after = model.score(features("alpha", 2)) - model.score(features("alpha", 1));

        assertTrue(after > before,
                "Click feedback should widen the clicked document's margin over the unclicked one");
    }

    @Test
    void testTrainingWithoutClicksReturnsZero() {
        assertEquals(0, trainer.train());
        assertEquals(0, trainer.onClick(new ClickEvent("no-impression", 1, "u1", 1)));
        assertEquals(1, tracker.clickCount());
    }

    @Test
    void testBatchingWithTrainAfterClicks() {
        ClickFeedbackTrainer batched = new ClickFeedbackTrainer(extractor, model, tracker, 3, 10, 0.1);
        tracker.recordImpression("alpha", List.of(1, 2));

        assertEquals(0, batched.onClick(new ClickEvent("alpha", 2, "u2", 2)));
        assertEquals(0, batched.onClick(new ClickEvent("alpha", 2, "u2", 2)));
        // Third click crosses the threshold and triggers training.
        int pairs = batched.onClick(new ClickEvent("alpha", 2, "u2", 2));
        assertEquals(3, pairs, "Each of the three clicks yields one preference pair");
    }

    private QueryDocumentFeatures features(String query, int docId) {
        return extractor.extract(query, docId, 0);
    }
}
