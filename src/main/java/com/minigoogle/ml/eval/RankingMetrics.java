package com.minigoogle.ml.eval;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Standard graded-ranking metrics used by the offline quality harness.
 *
 * <p>All metrics are computed from a served document-id ordering and a
 * per-document relevance judgment (graded 0-4). {@link #evaluate} aggregates
 * NDCG@10, MAP, recall@5, precision@5 and MRR over a ranked list.</p>
 *
 * <p>These are textbook definitions (NDCG as used by the TREC relevance
 * community, MAP/recall/precision over the top-K, MRR for the first relevant
 * hit) so the numbers they produce are reproducible and defensible in an
 * interview.</p>
 */
public final class RankingMetrics {

    /** NDCG@K uses 1/log2(rank+1) discount with (2^rel - 1) gain. */
    public static final int DEFAULT_K = 10;

    private RankingMetrics() {
    }

    public record Scores(double ndcgAt10, double map, double recallAt5, double precisionAt5, double mrr) {

        @Override
        public String toString() {
            return String.format("NDCG@10=%.4f MAP=%.4f Recall@5=%.4f Precision@5=%.4f MRR=%.4f",
                    ndcgAt10, map, recallAt5, precisionAt5, mrr);
        }
    }

    /**
     * Evaluates a ranked list against per-document relevance judgments.
     *
     * @param ranked    The served document ids in rank order (top-K of interest).
     * @param relevance Graded relevance (0 = not relevant, 1-4 relevant).
     */
    public static Scores evaluate(List<Integer> ranked, Map<Integer, Integer> relevance) {
        List<Integer> top5 = ranked.size() > 5 ? ranked.subList(0, 5) : ranked;
        return new Scores(
                // ndcgAt truncates to k internally; pass the full ranking so the
                // cutoff is applied in exactly one place.
                ndcgAt(ranked, relevance, DEFAULT_K),
                map(ranked, relevance),
                recallAt(top5, relevance),
                precisionAt(top5, relevance),
                mrr(ranked, relevance));
    }

    /**
     * NDCG@K with the standard TREC formulation: gain {@code 2^rel - 1},
     * discount {@code 1/log2(rank+1)}, normalized by the ideal ranking.
     *
     * <p>The ideal ranking (IDCG) is the best achievable ordering of the judged
     * documents truncated at {@code k}, and is deliberately <em>independent of
     * how many documents the system returned</em>. Scaling the ideal down to the
     * served list length would let a system that returns a single relevant
     * document score a perfect 1.0, which rewards returning fewer results.</p>
     *
     * <p>Negative judgments (some qrel formats use -1 for "explicitly
     * non-relevant") are clamped to 0, so they contribute no gain rather than a
     * negative one.</p>
     *
     * @param ranked    served document ids in rank order
     * @param relevance graded relevance per document id
     * @param k         cutoff; values &lt;= 0 yield 0.0
     * @return NDCG@K in [0, 1], or 0.0 when no relevant document is judged
     */
    public static double ndcgAt(List<Integer> ranked, Map<Integer, Integer> relevance, int k) {
        if (ranked == null || ranked.isEmpty() || relevance == null || relevance.isEmpty() || k <= 0) {
            return 0.0;
        }

        double dcg = 0.0;
        int served = Math.min(k, ranked.size());
        for (int i = 0; i < served; i++) {
            dcg += gain(relevance.getOrDefault(ranked.get(i), 0)) / log2(i + 2);
        }

        // Ideal ranking: the k highest judgments available, regardless of what
        // the system actually returned.
        List<Integer> ideal = relevance.values().stream()
                .map(RankingMetrics::clampGrade)
                .filter(grade -> grade > 0)
                .sorted(Comparator.reverseOrder())
                .limit(k)
                .toList();

        double idcg = 0.0;
        for (int i = 0; i < ideal.size(); i++) {
            idcg += gain(ideal.get(i)) / log2(i + 2);
        }

        return idcg <= 0.0 ? 0.0 : dcg / idcg;
    }

    /** Convenience alias for the reported headline metric. */
    public static double ndcgAt10(List<Integer> ranked, Map<Integer, Integer> relevance) {
        return ndcgAt(ranked, relevance, DEFAULT_K);
    }

    /** Exponential gain {@code 2^rel - 1}, with negative judgments treated as 0. */
    private static double gain(int grade) {
        return Math.pow(2, clampGrade(grade)) - 1.0;
    }

    private static int clampGrade(int grade) {
        return Math.max(0, grade);
    }

    /**
     * Mean average precision at the judgment cutoff (all relevant documents).
     */
    public static double map(List<Integer> ranked, Map<Integer, Integer> relevance) {
        List<Integer> relevant = relevance.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(Map.Entry::getKey)
                .toList();
        if (relevant.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        int hits = 0;
        for (int i = 0; i < ranked.size(); i++) {
            if (relevance.getOrDefault(ranked.get(i), 0) > 0) {
                hits++;
                sum += (double) hits / (i + 1);
            }
        }
        return relevant.isEmpty() ? 0.0 : sum / relevant.size();
    }

    public static double recallAt(List<Integer> ranked, Map<Integer, Integer> relevance) {
        long relevant = relevance.values().stream().filter(v -> v > 0).count();
        if (relevant == 0) {
            return 0.0;
        }
        long hits = ranked.stream().filter(id -> relevance.getOrDefault(id, 0) > 0).count();
        return (double) hits / relevant;
    }

    /**
     * Recall at cutoff K over the served ranking (BEIR Recall@100 uses the full
     * candidate list).
     */
    public static double recallAtK(List<Integer> ranked, Map<Integer, Integer> relevance, int k) {
        long relevant = relevance.values().stream().filter(v -> v > 0).count();
        if (relevant == 0) {
            return 0.0;
        }
        List<Integer> topK = ranked.size() > k ? ranked.subList(0, k) : ranked;
        long hits = topK.stream().filter(id -> relevance.getOrDefault(id, 0) > 0).count();
        return (double) hits / relevant;
    }

    /**
     * MRR at cutoff K: reciprocal rank of the first relevant hit, capped at K
     * (0 if the first relevant hit is beyond K or absent).
     */
    public static double mrrAtK(List<Integer> ranked, Map<Integer, Integer> relevance, int k) {
        int limit = Math.min(k, ranked.size());
        for (int i = 0; i < limit; i++) {
            if (relevance.getOrDefault(ranked.get(i), 0) > 0) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * MAP over the top-K served ranking, divided by the total number of judged
     * relevant documents.
     */
    public static double mapAtK(List<Integer> ranked, Map<Integer, Integer> relevance, int k) {
        List<Integer> relevant = relevance.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(Map.Entry::getKey)
                .toList();
        if (relevant.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        int hits = 0;
        int limit = Math.min(k, ranked.size());
        for (int i = 0; i < limit; i++) {
            if (relevance.getOrDefault(ranked.get(i), 0) > 0) {
                hits++;
                sum += (double) hits / (i + 1);
            }
        }
        return sum / relevant.size();
    }

    public static double precisionAt(List<Integer> ranked, Map<Integer, Integer> relevance) {
        if (ranked.isEmpty()) {
            return 0.0;
        }
        long hits = ranked.stream().filter(id -> relevance.getOrDefault(id, 0) > 0).count();
        return (double) hits / ranked.size();
    }

    public static double mrr(List<Integer> ranked, Map<Integer, Integer> relevance) {
        for (int i = 0; i < ranked.size(); i++) {
            if (relevance.getOrDefault(ranked.get(i), 0) > 0) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
