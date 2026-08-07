package com.minigoogle.ml.features;

/**
 * The query-document features extracted for learning-to-rank.
 *
 * <p>Feature order in {@link QueryDocumentFeatures} is fixed to this enum's
 * declaration order so feature vectors, model weights and serialized output
 * always agree.</p>
 */
public enum FeatureName {
    /** BM25-style TF-saturated lexical score derived from the document body. */
    BM25,
    /** PageRank authority score (normalized to [0, 1]). */
    PAGE_RANK,
    /** Fraction of query terms that appear in the document title. */
    TITLE_MATCH,
    /** Fraction of query terms that appear in the document URL. */
    URL_MATCH,
    /** Fraction of distinct query terms present in the document body. */
    TERM_OVERLAP,
    /** Cosine similarity between the query and document embeddings. */
    SEMANTIC_SIMILARITY,
    /** Normalized (log-scaled) document length. */
    DOC_LENGTH,
    /** Reciprocal rank of the result position, 1/(position + 1). */
    POSITION
}
