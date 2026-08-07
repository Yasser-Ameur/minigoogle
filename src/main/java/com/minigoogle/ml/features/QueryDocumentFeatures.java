package com.minigoogle.ml.features;

/**
 * A feature vector for a single query-document pair.
 *
 * @param query      The query the features were extracted for.
 * @param documentId The document ID.
 * @param values     Feature values aligned with {@link FeatureName} order.
 */
public record QueryDocumentFeatures(
        String query,
        int documentId,
        double[] values
) {
    public QueryDocumentFeatures {
        values = values.clone();
    }

    /**
     * @param values Feature values aligned with {@link FeatureName} order.
     * @return A feature vector for the given values.
     */
    public static QueryDocumentFeatures of(double[] values) {
        return new QueryDocumentFeatures("", -1, values);
    }

    /**
     * Returns the value of a specific feature.
     */
    public double get(FeatureName name) {
        return values[name.ordinal()];
    }

    /**
     * Returns a defensive copy of the feature values.
     */
    public double[] values() {
        return values.clone();
    }

    /**
     * The number of features in the vector.
     */
    public int size() {
        return values.length;
    }
}
