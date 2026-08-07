package com.minigoogle.ml.ltr;

import com.minigoogle.ml.features.FeatureName;

import java.util.List;

/**
 * Pairwise learning-to-rank trainer (RankNet-style logistic loss).
 *
 * <p>For each preference pair the model is pushed so the preferred document
 * scores strictly higher than the non-preferred one. The update is a gradient
 * descent step on {@code log(1 + exp(-(score(p) - score(n))))}:
 *
 * <pre>
 *   w += lr * sigmoid(-(s_p - s_n)) * (x_p - x_n)
 * </pre>
 *
 * Pairs are shuffled each epoch and a small weight decay prevents feature
 * weights from exploding on very dense click logs.</p>
 */
public final class PairwiseRankerTrainer {

    private static final double WEIGHT_DECAY = 1e-4;

    private PairwiseRankerTrainer() {
    }

    /**
     * Trains a linear ranking model on a set of preference pairs.
     *
     * @param model    The model to update in place.
     * @param pairs    The preference pairs.
     * @param epochs   Number of passes over the pairs.
     * @param lr       Learning rate.
     * @return The number of pairs used for training.
     */
    public static int train(LinearRankingModel model, List<TrainingPair> pairs,
                            int epochs, double lr) {
        if (pairs == null || pairs.isEmpty() || epochs <= 0) {
            return pairs == null ? 0 : pairs.size();
        }

        FeatureName[] names = FeatureName.values();
        double[] currentWeights = model.weights();
        double[] step = new double[names.length];
        for (int epoch = 0; epoch < epochs; epoch++) {
            java.util.List<TrainingPair> shuffled = new java.util.ArrayList<>(pairs);
            java.util.Collections.shuffle(shuffled);

            for (TrainingPair pair : shuffled) {
                double scoreP = model.score(pair.preferred());
                double scoreN = model.score(pair.nonPreferred());
                double margin = scoreP - scoreN;
                double weight = sigmoid(-margin);

                for (int i = 0; i < names.length; i++) {
                    step[i] = lr * (weight * (pair.preferred().get(names[i])
                            - pair.nonPreferred().get(names[i]))
                            - WEIGHT_DECAY * currentWeights[i]);
                }
                model.applyGradient(step);
            }
        }
        return pairs.size();
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
}
