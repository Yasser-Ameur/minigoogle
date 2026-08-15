package com.minigoogle.ml.eval;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies NDCG@K against hand-computed values from the standard TREC
 * formulation: gain {@code 2^rel - 1}, discount {@code 1/log2(rank+1)},
 * normalized by the ideal ranking of the judged documents truncated at K.
 *
 * <p>Expected values below are derived by hand in the comments rather than by
 * re-implementing the formula in the test, so a shared misreading of the
 * definition cannot make a wrong implementation look correct.</p>
 */
class RankingMetricsTest {

    private static final double EPS = 1e-9;

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    private static Map<Integer, Integer> judgments(int... idGradePairs) {
        Map<Integer, Integer> rel = new LinkedHashMap<>();
        for (int i = 0; i < idGradePairs.length; i += 2) {
            rel.put(idGradePairs[i], idGradePairs[i + 1]);
        }
        return rel;
    }

    // ── Core definition ──

    @Test
    void perfectRankingScoresOne() {
        // Judged: d1=3, d2=2, d3=1. Served in exactly ideal order.
        Map<Integer, Integer> rel = judgments(1, 3, 2, 2, 3, 1);
        assertEquals(1.0, RankingMetrics.ndcgAt(List.of(1, 2, 3), rel, 10), EPS);
    }

    @Test
    void reversedRankingScoresBelowPerfect() {
        Map<Integer, Integer> rel = judgments(1, 3, 2, 2, 3, 1);
        double reversed = RankingMetrics.ndcgAt(List.of(3, 2, 1), rel, 10);
        assertTrue(reversed < 1.0, "a reversed ranking must score below the ideal");
        assertTrue(reversed > 0.0, "a reversed ranking still retrieves relevant docs");
    }

    @Test
    void matchesHandComputedValueForKnownRanking() {
        // Judged: d1=3, d2=2, d3=1 (nothing else relevant).
        // Served order: d2, d1, d3  → grades 2, 3, 1
        //   DCG  = (2^2-1)/log2(2) + (2^3-1)/log2(3) + (2^1-1)/log2(4)
        //        = 3/1 + 7/1.5849625007 + 1/2
        //   IDCG = (2^3-1)/log2(2) + (2^2-1)/log2(3) + (2^1-1)/log2(4)
        //        = 7/1 + 3/1.5849625007 + 1/2
        Map<Integer, Integer> rel = judgments(1, 3, 2, 2, 3, 1);

        double dcg = 3.0 / log2(2) + 7.0 / log2(3) + 1.0 / log2(4);
        double idcg = 7.0 / log2(2) + 3.0 / log2(3) + 1.0 / log2(4);

        assertEquals(dcg / idcg, RankingMetrics.ndcgAt(List.of(2, 1, 3), rel, 10), EPS);
    }

    @Test
    void irrelevantResultsScoreZero() {
        Map<Integer, Integer> rel = judgments(1, 3, 2, 2);
        // Served documents carry no judgment at all → gain 0 at every rank.
        assertEquals(0.0, RankingMetrics.ndcgAt(List.of(90, 91, 92), rel, 10), EPS);
    }

    // ── The regression this class was written for ──

    @Test
    void idcgIsIndependentOfHowManyResultsWereReturned() {
        // Ten equally-relevant documents are judged; the system returns only one.
        // The ideal ranking is still ten documents deep, so returning a single
        // relevant result must NOT score a perfect 1.0.
        Map<Integer, Integer> rel = new LinkedHashMap<>();
        for (int id = 1; id <= 10; id++) {
            rel.put(id, 3);
        }

        double single = RankingMetrics.ndcgAt(List.of(1), rel, 10);

        // DCG = 7/log2(2) = 7. IDCG = sum over 10 ideal docs of 7/log2(i+2).
        double idcg = 0.0;
        for (int i = 0; i < 10; i++) {
            idcg += 7.0 / log2(i + 2);
        }
        assertEquals(7.0 / idcg, single, EPS);
        assertTrue(single < 0.25,
                "one relevant hit out of ten judged must score far below 1.0, was " + single);
    }

    @Test
    void returningMoreRelevantResultsScoresStrictlyHigher() {
        Map<Integer, Integer> rel = new LinkedHashMap<>();
        for (int id = 1; id <= 10; id++) {
            rel.put(id, 3);
        }

        double one = RankingMetrics.ndcgAt(List.of(1), rel, 10);
        double five = RankingMetrics.ndcgAt(List.of(1, 2, 3, 4, 5), rel, 10);
        double ten = RankingMetrics.ndcgAt(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), rel, 10);

        assertTrue(one < five, "five relevant hits must beat one (" + one + " vs " + five + ")");
        assertTrue(five < ten, "ten relevant hits must beat five (" + five + " vs " + ten + ")");
        assertEquals(1.0, ten, EPS, "retrieving all ten in ideal order is perfect");
    }

    // ── Cutoff behavior ──

    @Test
    void cutoffIgnoresResultsBeyondK() {
        Map<Integer, Integer> rel = judgments(1, 3, 99, 3);
        // d99 is relevant but sits at rank 11, beyond the K=10 cutoff.
        List<Integer> ranked = List.of(1, 20, 21, 22, 23, 24, 25, 26, 27, 28, 99);

        double atTen = RankingMetrics.ndcgAt(ranked, rel, 10);
        double atEleven = RankingMetrics.ndcgAt(ranked, rel, 11);

        assertTrue(atEleven > atTen, "including rank 11 must add the relevant document's gain");
    }

    @Test
    void idcgTruncatesAtKSoDeepJudgmentsDoNotPenalizeUnfairly() {
        // 20 relevant documents judged, K=10: the ideal can only hold 10, so
        // retrieving the best 10 in order is a perfect score.
        Map<Integer, Integer> rel = new LinkedHashMap<>();
        for (int id = 1; id <= 20; id++) {
            rel.put(id, 2);
        }
        List<Integer> topTen = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertEquals(1.0, RankingMetrics.ndcgAt(topTen, rel, 10), EPS);
    }

    @Test
    void gradedRelevanceOutranksBinaryOrdering() {
        // Putting the highest grade first must beat putting a lower grade first.
        Map<Integer, Integer> rel = judgments(1, 4, 2, 1);
        double highFirst = RankingMetrics.ndcgAt(List.of(1, 2), rel, 10);
        double lowFirst = RankingMetrics.ndcgAt(List.of(2, 1), rel, 10);
        assertTrue(highFirst > lowFirst,
                "grade 4 before grade 1 must outrank the reverse");
        assertEquals(1.0, highFirst, EPS);
    }

    // ── Edge cases ──

    @Test
    void emptyAndDegenerateInputsYieldZero() {
        Map<Integer, Integer> rel = judgments(1, 3);
        assertEquals(0.0, RankingMetrics.ndcgAt(List.of(), rel, 10), EPS, "empty ranking");
        assertEquals(0.0, RankingMetrics.ndcgAt(null, rel, 10), EPS, "null ranking");
        assertEquals(0.0, RankingMetrics.ndcgAt(List.of(1), Map.of(), 10), EPS, "no judgments");
        assertEquals(0.0, RankingMetrics.ndcgAt(List.of(1), null, 10), EPS, "null judgments");
        assertEquals(0.0, RankingMetrics.ndcgAt(List.of(1), rel, 0), EPS, "k = 0");
        assertEquals(0.0, RankingMetrics.ndcgAt(List.of(1), rel, -5), EPS, "negative k");
    }

    @Test
    void allJudgmentsZeroYieldsZeroRatherThanDivideByZero() {
        Map<Integer, Integer> rel = judgments(1, 0, 2, 0);
        double score = RankingMetrics.ndcgAt(List.of(1, 2), rel, 10);
        assertEquals(0.0, score, EPS, "no relevant document means an undefined ideal → 0");
    }

    @Test
    void negativeJudgmentsAreTreatedAsNonRelevant() {
        // Some qrel formats use -1 for explicitly non-relevant. It must not
        // produce a negative gain (2^-1 - 1 = -0.5), which would let a bad
        // result drag the score below zero.
        Map<Integer, Integer> rel = judgments(1, 3, 2, -1);
        double score = RankingMetrics.ndcgAt(List.of(2, 1), rel, 10);
        assertTrue(score >= 0.0, "score must never go negative, was " + score);

        // Identical to treating d2 as grade 0.
        Map<Integer, Integer> asZero = judgments(1, 3, 2, 0);
        assertEquals(RankingMetrics.ndcgAt(List.of(2, 1), asZero, 10), score, EPS);
    }

    @Test
    void scoreIsAlwaysWithinUnitInterval() {
        Map<Integer, Integer> rel = judgments(1, 4, 2, 3, 3, 2, 4, 1, 5, 0);
        List<List<Integer>> rankings = List.of(
                List.of(1, 2, 3, 4, 5),
                List.of(5, 4, 3, 2, 1),
                List.of(3, 1, 5, 2, 4),
                List.of(99, 98, 97),
                List.of(1));
        for (List<Integer> ranking : rankings) {
            double score = RankingMetrics.ndcgAt(ranking, rel, 10);
            assertTrue(score >= 0.0 && score <= 1.0,
                    "NDCG must lie in [0,1] for " + ranking + ", was " + score);
        }
    }

    @Test
    void ndcgAt10AliasMatchesExplicitCutoff() {
        Map<Integer, Integer> rel = judgments(1, 3, 2, 2, 3, 1);
        List<Integer> ranked = List.of(2, 1, 3);
        assertEquals(RankingMetrics.ndcgAt(ranked, rel, 10),
                RankingMetrics.ndcgAt10(ranked, rel), EPS);
    }

    @Test
    void evaluateReportsTheSameNdcgAsTheDirectCall() {
        Map<Integer, Integer> rel = judgments(1, 3, 2, 2, 3, 1, 4, 0);
        List<Integer> ranked = List.of(4, 2, 1, 3);
        assertEquals(RankingMetrics.ndcgAt(ranked, rel, 10),
                RankingMetrics.evaluate(ranked, rel).ndcgAt10(), EPS);
    }

    @Test
    void evaluateIsUnaffectedByResultsBeyondTheCutoff() {
        // A long tail of irrelevant results past rank 10 must not change NDCG@10.
        Map<Integer, Integer> rel = judgments(1, 3, 2, 2);
        List<Integer> shortList = List.of(1, 2);
        List<Integer> paddedList = List.of(1, 2, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59);

        assertEquals(RankingMetrics.ndcgAt(shortList, rel, 10),
                RankingMetrics.ndcgAt(paddedList, rel, 10), EPS);
    }
}
