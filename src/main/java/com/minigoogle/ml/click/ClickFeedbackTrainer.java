package com.minigoogle.ml.click;

import com.minigoogle.ml.features.FeatureExtractor;
import com.minigoogle.ml.features.QueryDocumentFeatures;
import com.minigoogle.ml.ltr.LinearRankingModel;
import com.minigoogle.ml.ltr.PairwiseRankerTrainer;
import com.minigoogle.ml.ltr.TrainingPair;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns click feedback into learning-to-rank training signal.
 *
 * <p>Every {@link #onClick} records the click and, once {@code trainAfterClicks}
 * new clicks have accumulated, rebuilds the preference pairs from the click log
 * and refines the ranking model with a short pairwise training pass.</p>
 *
 * <p>Features are resolved through a {@link ClickFeatureProvider}: a standalone
 * node re-extracts them from its corpus via {@link FeatureExtractor}; a
 * coordinator resolves the exact served vectors from its impression log. In
 * both cases train-time features equal serve-time features.</p>
 */
public class ClickFeedbackTrainer {

    private final ClickFeatureProvider featureProvider;
    private final LinearRankingModel model;
    private final ClickTracker tracker;
    private final int trainAfterClicks;
    private final int epochs;
    private final double learningRate;
    private int clicksSinceLastTrain;

    public ClickFeedbackTrainer(FeatureExtractor featureExtractor,
                                LinearRankingModel model,
                                ClickTracker tracker,
                                int trainAfterClicks,
                                int epochs,
                                double learningRate) {
        this((ClickFeatureProvider) featureExtractor, model, tracker,
                trainAfterClicks, epochs, learningRate);
    }

    public ClickFeedbackTrainer(ClickFeatureProvider featureProvider,
                                LinearRankingModel model,
                                ClickTracker tracker,
                                int trainAfterClicks,
                                int epochs,
                                double learningRate) {
        this.featureProvider = featureProvider;
        this.model = model;
        this.tracker = tracker;
        this.trainAfterClicks = trainAfterClicks;
        this.epochs = epochs;
        this.learningRate = learningRate;
    }

    /**
     * Records a click and trains the model once enough new clicks have arrived.
     *
     * @return The number of preference pairs used in this round of training,
     *         or 0 if no training was triggered.
     */
    public int onClick(ClickEvent event) {
        tracker.recordClick(event);
        clicksSinceLastTrain++;
        if (clicksSinceLastTrain >= trainAfterClicks) {
            clicksSinceLastTrain = 0;
            return train();
        }
        return 0;
    }

    /**
     * Trains the model on all current click-derived preferences.
     *
     * @return The number of preference pairs used, or 0 if none.
     */
    public int train() {
        List<ClickPreference> preferences = tracker.buildPreferences();
        if (preferences.isEmpty()) {
            return 0;
        }
        List<TrainingPair> pairs = new ArrayList<>(preferences.size());
        for (ClickPreference preference : preferences) {
            QueryDocumentFeatures preferred = featureProvider.features(
                    preference.query(), preference.preferredDocId());
            QueryDocumentFeatures nonPreferred = featureProvider.features(
                    preference.query(), preference.nonPreferredDocId());
            pairs.add(new TrainingPair(preferred, nonPreferred));
        }
        return PairwiseRankerTrainer.train(model, pairs, epochs, learningRate);
    }
}
