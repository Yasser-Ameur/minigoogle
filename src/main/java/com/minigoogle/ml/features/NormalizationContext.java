package com.minigoogle.ml.features;

/**
 * Corpus-global statistics used to normalize raw features.
 *
 * <p>Standalone mode derives this from the full local corpus; the coordinator
 * derives it as the maximum over the shards' reported stats (a defensible
 * global approximation that requires no corpus replication).</p>
 *
 * @param maxPageRank Highest PageRank observed in the corpus context.
 * @param maxDocLength Longest document (token count) in the corpus context.
 */
public record NormalizationContext(double maxPageRank, double maxDocLength) {

    public static final NormalizationContext EMPTY = new NormalizationContext(0.0, 0.0);
}
