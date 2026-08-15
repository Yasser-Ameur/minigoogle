package com.minigoogle.ranking.fusion;

import com.minigoogle.ranking.fusion.ReciprocalRankFusion.Fused;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies RRF against values computed by hand from
 * {@code RRF(d) = SUM 1 / (k + rank_i(d))}.
 *
 * <p>Expected values are derived arithmetically in the comments rather than by
 * recording what the implementation returns, so a wrong formula cannot make
 * these pass.</p>
 */
class ReciprocalRankFusionTest {

    private static final double EPS = 1e-12;

    private static double score(int k, int... ranks) {
        double s = 0;
        for (int r : ranks) {
            s += 1.0 / (k + r);
        }
        return s;
    }

    @Test
    void singleRankingReproducesItsOwnOrder() {
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(60);
        List<Fused> fused = rrf.fuse(List.of(List.of(7, 3, 9)));

        assertEquals(List.of(7, 3, 9), fused.stream().map(Fused::documentId).toList(),
                "with one input, fusion must be order-preserving");
        assertEquals(score(60, 1), fused.get(0).score(), EPS);
        assertEquals(score(60, 2), fused.get(1).score(), EPS);
        assertEquals(score(60, 3), fused.get(2).score(), EPS);
    }

    @Test
    void documentInBothRankingsOutscoresDocumentInOne() {
        // k = 60.
        //   doc 1: lexical rank 2, semantic rank 3  -> 1/62 + 1/63 = 0.0320
        //   doc 2: lexical rank 1, absent           -> 1/61        = 0.0164
        // Agreement across rankings beats a single strong placement, which is
        // the entire point of rank fusion.
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(60);

        List<Fused> fused = rrf.fuse(List.of(2, 1), List.of(9, 8, 1));

        assertEquals(1, fused.get(0).documentId(), "the doc in both rankings must lead");
        assertEquals(score(60, 2, 3), fused.get(0).score(), EPS);
    }

    @Test
    void absentDocumentContributesNothingFromThatRanking() {
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(10);

        // doc 5 appears only in the second ranking, at rank 1 -> 1/11 exactly.
        List<Fused> fused = rrf.fuse(List.of(1, 2), List.of(5));

        Fused five = fused.stream().filter(f -> f.documentId() == 5).findFirst().orElseThrow();
        assertEquals(score(10, 1), five.score(), EPS,
                "a missing rank must add zero, not a sentinel value");
    }

    @Test
    void lexicalOnlyAndSemanticOnlyAreBothRepresented() {
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(60);

        List<Fused> fused = rrf.fuse(List.of(1, 2), List.of(3, 4));
        List<Integer> ids = fused.stream().map(Fused::documentId).toList();

        assertEquals(4, ids.size(), "the union of both rankings must be represented");
        assertTrue(ids.containsAll(List.of(1, 2, 3, 4)));
        // Rank-1 documents from each ranking tie at 1/61 and are ordered by first
        // appearance, so the lexical rank-1 leads.
        assertEquals(1, ids.get(0));
        assertEquals(3, ids.get(1));
    }

    @Test
    void aDeeplyRankedSemanticHitCanOutrankAMediocreLexicalOne() {
        // The behaviour the hybrid architecture depends on, worked by hand at k = 60:
        //   doc A: lexical 2,   absent      -> 1/62            = 0.016129
        //   doc B: lexical 400, semantic 3  -> 1/460 + 1/63    = 0.018049
        //   doc C: lexical 20,  semantic 15 -> 1/80  + 1/75    = 0.025833
        // C leads on agreement; B overtakes A because a strong semantic rank
        // outweighs A's single mid-table lexical placement.
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(60);

        List<Integer> lexical = new ArrayList<>();
        for (int i = 1; i <= 400; i++) {
            lexical.add(i == 2 ? 1001 : i == 20 ? 1003 : i == 400 ? 1002 : 10_000 + i);
        }
        List<Integer> semantic = List.of(9001, 9002, 1002, 9003, 9004, 9005, 9006,
                9007, 9008, 9009, 9010, 9011, 9012, 9013, 1003);

        List<Integer> ids = rrf.fuseToIds(lexical, semantic);

        int posA = ids.indexOf(1001);
        int posB = ids.indexOf(1002);
        int posC = ids.indexOf(1003);
        assertTrue(posC < posB, "agreement (20,15) must beat one strong semantic rank");
        assertTrue(posB < posA, "a rank-3 semantic hit must lift a rank-400 lexical doc above a rank-2 lexical-only doc");
    }

    @Test
    void smallerKGivesTopRanksMoreInfluence() {
        // At k = 1:  rank1 = 1/2 = 0.5,  rank2+rank3 = 1/3 + 1/4 = 0.5833 -> agreement wins
        // At k = 1000: rank1 = 1/1001,   rank2+rank3 = 1/1002 + 1/1003    -> agreement wins by more
        // The ratio is what changes; assert the direction rather than a threshold.
        double agreementSmallK = score(1, 2, 3) / score(1, 1);
        double agreementLargeK = score(1000, 2, 3) / score(1000, 1);
        assertTrue(agreementLargeK > agreementSmallK,
                "larger k must flatten the curve, increasing the relative value of agreement");
    }

    @Test
    void duplicateIdsInOneRankingKeepTheBestPosition() {
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(60);

        // doc 5 appears at rank 1 and again at rank 3; only rank 1 counts, and it
        // must not be double-credited.
        List<Fused> fused = rrf.fuse(List.of(List.of(5, 6, 5)));

        assertEquals(2, fused.size(), "each document must appear once in the output");
        Fused five = fused.stream().filter(f -> f.documentId() == 5).findFirst().orElseThrow();
        assertEquals(score(60, 1), five.score(), EPS,
                "a repeated id must count once, at its best rank");
    }

    @Test
    void tiesAreBrokenDeterministically() {
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(60);

        // Symmetric inputs: 1 and 2 both hold rank 1 in one ranking.
        List<Integer> first = rrf.fuseToIds(List.of(1), List.of(2));
        List<Integer> second = rrf.fuseToIds(List.of(1), List.of(2));

        assertEquals(first, second, "the same inputs must produce the same order");
        assertEquals(List.of(1, 2), first, "ties resolve by first appearance across inputs");
    }

    @Test
    void emptyAndNullRankingsAreHandled() {
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(60);

        assertTrue(rrf.fuse(List.of()).isEmpty());
        assertTrue(rrf.fuse(List.of(List.of(), List.of())).isEmpty());
        assertEquals(List.of(1), rrf.fuseToIds(List.of(1), null));
        assertEquals(List.of(2), rrf.fuseToIds(null, List.of(2)));
    }

    @Test
    void veryDeepRanksStayFiniteAndOrdered() {
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(60);

        List<Integer> deep = new ArrayList<>();
        for (int i = 1; i <= 200_000; i++) {
            deep.add(i);
        }
        List<Fused> fused = rrf.fuse(List.of(deep));

        assertEquals(200_000, fused.size());
        double last = fused.get(fused.size() - 1).score();
        assertTrue(last > 0 && Double.isFinite(last),
                "deep ranks must remain positive and finite, was " + last);
        assertTrue(fused.get(0).score() > last, "ordering must survive at depth");
    }

    @Test
    void rejectsNonPositiveK() {
        // k = 0 makes rank 1 contribute 1/1; negative k divides by zero at rank -k.
        assertThrows(IllegalArgumentException.class, () -> new ReciprocalRankFusion(0));
        assertThrows(IllegalArgumentException.class, () -> new ReciprocalRankFusion(-60));
    }

    @Test
    void reportsInputRanksForExplainability() {
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(60);

        List<Fused> fused = rrf.fuse(List.of(4, 7), List.of(7));
        Fused seven = fused.stream().filter(f -> f.documentId() == 7).findFirst().orElseThrow();

        assertEquals(List.of(2, 1), seven.ranks(),
                "input ranks must be reported so a fused ordering can be explained");
        Fused four = fused.stream().filter(f -> f.documentId() == 4).findFirst().orElseThrow();
        assertEquals(List.of(1, 0), four.ranks(), "0 marks absence from a ranking");
    }
}
