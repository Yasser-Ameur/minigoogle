package com.minigoogle.ranking.bm25;

/**
 * BM25 tuning parameters. Industry standard defaults are k1=1.5, b=0.75.
 *
 * @param k1              Controls term frequency saturation. Higher values give more weight to raw TF.
 * @param b               Controls document length normalization. 0 = no normalization, 1 = full normalization.
 * @param totalDocuments   Total number of documents in the corpus (N).
 * @param averageDocLength Average document length across the corpus (avgdl).
 */
public record BM25Parameters(
        double k1,
        double b,
        long totalDocuments,
        double averageDocLength
) {
    /**
     * Creates parameters with industry-standard defaults for k1 and b.
     */
    public static BM25Parameters withDefaults(long totalDocuments, double averageDocLength) {
        return new BM25Parameters(1.5, 0.75, totalDocuments, averageDocLength);
    }
}
