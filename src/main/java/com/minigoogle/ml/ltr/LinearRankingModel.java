package com.minigoogle.ml.ltr;

import com.minigoogle.ml.features.FeatureName;
import com.minigoogle.ml.features.QueryDocumentFeatures;

import java.util.Arrays;

/**
 * A linear learning-to-rank model (pointwise).
 *
 * <p>The score is a weighted sum of the normalized features plus a bias
 * term. Weights are initialized to sensible defaults that approximate a
 * blended BM25 + PageRank + title/semantic ranking; they are refined online
 * from click feedback by the {@link PairwiseRankerTrainer}.</p>
 */
public class LinearRankingModel implements RankingModel {

    private final double[] weights;
    private double bias;

    /**
     * Creates a model with the given weights, aligned with
     * {@link FeatureName} order, and a zero bias.
     */
    public LinearRankingModel(double[] weights) {
        this.weights = new double[FeatureName.values().length];
        System.arraycopy(weights, 0, this.weights, 0, Math.min(weights.length, this.weights.length));
        this.bias = 0.0;
    }

    /**
     * Creates a model with default weights that emphasize lexical relevance,
     * authority and title matches while slightly penalizing very long docs.
     */
    public LinearRankingModel() {
        this(new double[]{
                0.35,  // BM25
                0.20,  // PAGE_RANK
                0.20,  // TITLE_MATCH
                0.05,  // URL_MATCH
                0.05,  // TERM_OVERLAP
                0.10,  // SEMANTIC_SIMILARITY
                -0.05, // DOC_LENGTH
                0.10   // POSITION
        });
    }

    @Override
    public double score(QueryDocumentFeatures features) {
        double score = bias;
        for (int i = 0; i < weights.length && i < features.size(); i++) {
            score += weights[i] * features.get(FeatureName.values()[i]);
        }
        return score;
    }

    /**
     * Returns a copy of the current weights.
     */
    public double[] weights() {
        return weights.clone();
    }

    /**
     * Returns the current bias term.
     */
    public double bias() {
        return bias;
    }

    /**
     * Replaces the weights and bias in place.
     */
    public void setParameters(double[] weights, double bias) {
        System.arraycopy(weights, 0, this.weights, 0, Math.min(weights.length, this.weights.length));
        this.bias = bias;
    }

    /**
     * Applies a gradient step: {@code weights += delta}.
     */
    public void applyGradient(double[] delta) {
        for (int i = 0; i < weights.length && i < delta.length; i++) {
            weights[i] += delta[i];
        }
    }

    @Override
    public String toString() {
        return "LinearRankingModel{weights=" + Arrays.toString(weights) + ", bias=" + bias + "}";
    }
}
