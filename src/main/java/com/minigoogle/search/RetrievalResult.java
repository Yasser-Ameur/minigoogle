package com.minigoogle.search;

import com.minigoogle.ranking.model.RankedDocument;

import java.util.List;

/**
 * Output of the retrieval stage: candidates that are pre-ranked by BM25 +
 * PageRank, hybrid recall and cross-encoder re-ranking, plus a spell-check
 * suggestion when the lexical match failed.
 *
 * <p>Learning-to-rank is intentionally absent here — final ranking is owned by
 * {@link com.minigoogle.ranking.pipeline.GlobalRankingPipeline}, which runs
 * identically on a standalone node and on the coordinator.
 */
public record RetrievalResult(List<RankedDocument> ranked, String didYouMean) {
}
