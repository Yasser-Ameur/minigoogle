package com.minigoogle.ranking.pipeline;

import com.minigoogle.ml.features.FeatureNormalizer;
import com.minigoogle.ml.features.NormalizationContext;
import com.minigoogle.ml.ltr.RankingModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The single shared final-ranking pipeline used by both standalone and
 * distributed execution.
 *
 * <p>The pipeline is deliberately thin: it normalizes each candidate's raw
 * features against a {@link NormalizationContext}, scores them with a shared
 * {@link RankingModel}, and sorts. All corpus-global normalization and all
 * rank-position assignment happen here — never on the shard that produced the
 * candidates. This guarantees a document served by the coordinator is scored
 * by the exact same code as one served by a standalone node.</p>
 *
 * <p>The {@code POSITION} feature is derived from the candidate's index in the
 * input (pre-ranking) order, matching the retrieval stage's relative ordering
 * (hybrid-fused score, cross-encoder re-rank) rather than the final order, so
 * the model learns position-independent relevance.</p>
 */
public final class GlobalRankingPipeline {

    private GlobalRankingPipeline() {
    }

    /**
     * Normalizes, scores and sorts the candidates in descending score order.
     *
     * @param query      The search query.
     * @param candidates Candidates from the retrieval stage, in candidate
     *                   order (used for the {@code POSITION} feature).
     * @param context    Corpus-global normalization context.
     * @param model      The shared ranking model.
     * @return The candidates in final rank order, never null.
     */
    public static List<RankedResult> rank(String query,
                                          List<RankedCandidate> candidates,
                                          NormalizationContext context,
                                          RankingModel model) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<RankedResult> results = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            RankedCandidate candidate = candidates.get(i);
            var features = FeatureNormalizer.normalize(
                    candidate.rawFeatures(), context, i);
            double score = model.score(features);
            results.add(new RankedResult(candidate, features, score, 0));
        }
        results.sort(Comparator.comparingDouble(RankedResult::score).reversed());
        for (int i = 0; i < results.size(); i++) {
            results.set(i, new RankedResult(
                    results.get(i).candidate(),
                    results.get(i).features(),
                    results.get(i).score(),
                    i));
        }
        return results;
    }
}
